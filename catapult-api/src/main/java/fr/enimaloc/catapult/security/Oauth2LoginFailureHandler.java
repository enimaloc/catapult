package fr.enimaloc.catapult.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Only the primary Twitch login is retried here: xbox/steam/battlenet failures
 * come from a "link a secondary provider" flow that runs on top of an already
 * authenticated Twitch session (see CatapultOAuth2UserService#handleSecondaryLink) —
 * invalidating the session to retry would just log that user out of their real session.
 *
 * A single automatic retry covers transient/technical failures (Twitch API hiccup,
 * a stale authorization_request in session, ...) that a fresh attempt commonly fixes.
 * Known business-rule errors (not whitelisted, invite already used, ...) are
 * deterministic — retrying changes nothing — so they skip straight to the error page.
 */
@Slf4j
@Component
public class Oauth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final String RETRY_COOKIE = "catapult_oauth2_retry";
    private static final int RETRY_COOKIE_MAX_AGE_SECONDS = 60;
    private static final Set<String> BUSINESS_ERROR_CODES = Set.of(
            "not_whitelisted", "invalid_invite", "alpha_full",
            "system_account_login_forbidden", "unauthorized");

    @Value("${app.web-url:http://localhost:8081}")
    private String webUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String errorCode = errorCodeOf(exception);
        log.error("OAuth2 login failed: [{}] {}", exception.getClass().getSimpleName(), exception.getMessage(), exception);

        String registrationId = registrationIdOf(request);
        if ("twitch".equals(registrationId) && !BUSINESS_ERROR_CODES.contains(errorCode) && !alreadyRetried(request)) {
            markRetried(request, response);
            if (request.getSession(false) != null) {
                request.getSession(false).invalidate();
            }
            response.sendRedirect("/oauth2/authorization/twitch");
            return;
        }

        clearRetryMarker(request, response);
        response.sendRedirect(errorRedirectUrl(errorCode));
    }

    private static String registrationIdOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    private static String errorCodeOf(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oauthEx
                ? oauthEx.getError().getErrorCode()
                : null;
    }

    private static boolean alreadyRetried(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie cookie : cookies) {
            if (RETRY_COOKIE.equals(cookie.getName())) return true;
        }
        return false;
    }

    private static void markRetried(HttpServletRequest request, HttpServletResponse response) {
        setRetryCookie(request, response, RETRY_COOKIE_MAX_AGE_SECONDS);
    }

    private static void clearRetryMarker(HttpServletRequest request, HttpServletResponse response) {
        setRetryCookie(request, response, 0);
    }

    private static void setRetryCookie(HttpServletRequest request, HttpServletResponse response, int maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(RETRY_COOKIE, "1")
                .httpOnly(true)
                .secure(request.isSecure())
                .path("/")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String errorRedirectUrl(String errorCode) {
        if (errorCode == null) return webUrl + "/login?error";
        return switch (errorCode) {
            case "not_whitelisted" -> webUrl + "/login?error=not_whitelisted";
            case "invalid_invite" -> webUrl + "/join?error=invalid_invite";
            case "alpha_full" -> webUrl + "/join?error=alpha_full";
            default -> webUrl + "/login?error";
        };
    }
}
