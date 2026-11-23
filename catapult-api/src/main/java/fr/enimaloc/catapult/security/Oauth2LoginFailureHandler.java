package fr.enimaloc.catapult.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Only the primary Twitch login is retried here: xbox/steam/battlenet/epic failures
 * come from a "link a secondary provider" flow that runs on top of an already
 * authenticated Twitch session (see CatapultOAuth2UserService#handleSecondaryLink) —
 * invalidating the session to retry would just log that user out of their real session.
 *
 * <p>A single automatic retry covers transient/technical failures (Twitch API hiccup,
 * a stale authorization_request in session, ...) that a fresh attempt commonly fixes.
 * Known business-rule errors (not whitelisted, invite already used, ...) are
 * deterministic — retrying changes nothing — so they skip straight to the error page.
 * The retry keeps catapult-web's state and invite code: both survive the dropped session in a cookie.
 */
@Slf4j
@Component
public class Oauth2LoginFailureHandler implements AuthenticationFailureHandler {

    private static final String RETRY_COOKIE = "catapult_oauth2_retry";
    private static final int RETRY_COOKIE_MAX_AGE_SECONDS = 60;
    private static final Set<String> BUSINESS_ERROR_CODES = Set.of(
            "not_whitelisted", "invalid_invite", "alpha_full",
            "system_account_login_forbidden", "unauthorized", "battlenet_unavailable", "battlenet_full",
            "epic_unavailable", "epic_full", "epic_already_linked",
            "battlenet_stats_unavailable", "battlenet_stats_failed", "battlenet_stats_mismatch",
            "battlenet_stats_cancelled",
            "bungie_unavailable", "bungie_failed", "bungie_mismatch", "bungie_cancelled",
            "osu_unavailable", "osu_failed", "osu_mismatch", "osu_cancelled");
    /**
     * Refusals of a link the member started from their channel's page: they go back to the card's tab (Integrations for
     * Epic, API for the personal stats' Blizzard, Bungie and osu!), the code as {@code error}, for the card to tell why
     * (TwitchLoginSuccessHandler sends a successful link to the same tab).
     */
    private static final Set<String> CHANNEL_LINK_ERROR_CODES = Set.of(
            "epic_unavailable", "epic_full", "epic_already_linked",
            "battlenet_stats_unavailable", "battlenet_stats_failed", "battlenet_stats_mismatch",
            "battlenet_stats_cancelled",
            "bungie_unavailable", "bungie_failed", "bungie_mismatch", "bungie_cancelled",
            "osu_unavailable", "osu_failed", "osu_mismatch", "osu_cancelled");
    /** The World of Warcraft import: a failure that is not one of its refusals is a failed import on the card. */
    private static final String WOW_IMPORT = "battlenet-stats";
    private static final Set<String> WOW_IMPORT_ERROR_CODES = Set.of(
            "battlenet_stats_unavailable", "battlenet_stats_failed", "battlenet_stats_mismatch",
            "battlenet_stats_cancelled");

    /** The Bungie.net link: a failure that is not one of its refusals is a failed link on the card. */
    private static final String BUNGIE = "bungie";
    private static final Set<String> BUNGIE_ERROR_CODES = Set.of(
            "bungie_unavailable", "bungie_failed", "bungie_mismatch", "bungie_cancelled");

    /** The osu! link: a failure that is not one of its refusals is a failed link on the card. */
    private static final String OSU = "osu";
    private static final Set<String> OSU_ERROR_CODES = Set.of(
            "osu_unavailable", "osu_failed", "osu_mismatch", "osu_cancelled");

    @Value("${app.web-url:http://localhost:8080}")
    private String webUrl;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String errorCode = errorCodeOf(exception);
        log.error("OAuth2 login failed: [{}] {}", exception.getClass().getSimpleName(), exception.getMessage(), exception);

        String registrationId = registrationIdOf(request);
        if (WOW_IMPORT.equals(registrationId) && "access_denied".equals(errorCode)) {
            // The member declined on Blizzard's consent page: not a failure
            errorCode = "battlenet_stats_cancelled";
        }
        if (WOW_IMPORT.equals(registrationId) && !"unauthorized".equals(errorCode)
                && !WOW_IMPORT_ERROR_CODES.contains(errorCode)) {
            errorCode = "battlenet_stats_failed";
        }
        if (BUNGIE.equals(registrationId) && "access_denied".equals(errorCode)) {
            // The member declined on Bungie's authorization page: not a failure
            errorCode = "bungie_cancelled";
        }
        if (BUNGIE.equals(registrationId) && !"unauthorized".equals(errorCode)
                && !BUNGIE_ERROR_CODES.contains(errorCode)) {
            errorCode = "bungie_failed";
        }
        if (OSU.equals(registrationId) && "access_denied".equals(errorCode)) {
            // The member declined on osu!'s authorization page: not a failure
            errorCode = "osu_cancelled";
        }
        if (OSU.equals(registrationId) && !"unauthorized".equals(errorCode) && !OSU_ERROR_CODES.contains(errorCode)) {
            errorCode = "osu_failed";
        }
        if ("twitch".equals(registrationId) && !BUSINESS_ERROR_CODES.contains(errorCode) && !alreadyRetried(request)) {
            markRetried(request, response);
            if (request.getSession(false) != null) {
                request.getSession(false).invalidate();
            }
            response.sendRedirect("/oauth2/authorization/twitch");
            return;
        }

        clearRetryMarker(request, response);
        if ("twitch".equals(registrationId)) {
            // The login is given up: catapult-web starts the next one with a state of its own
            InviteCodeRelayFilter.clearPendingWebState(request, response);
        }
        response.sendRedirect(errorRedirectUrl(errorCode, request));
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

    private String errorRedirectUrl(String errorCode, HttpServletRequest request) {
        if (errorCode == null) return webUrl + "/login?error";
        if (CHANNEL_LINK_ERROR_CODES.contains(errorCode)) {
            String login = sessionLogin(request.getSession(false));
            if (login != null) {
                return UriComponentsBuilder.fromUriString(webUrl)
                        .path("/channel/{username}")
                        .queryParam("error", errorCode)
                        .fragment(errorCode.startsWith("epic_") ? "integrations" : "api")
                        .buildAndExpand(login)
                        .toUriString();
            }
        }
        return switch (errorCode) {
            case "not_whitelisted" -> webUrl + "/login?error=not_whitelisted";
            case "invalid_invite" -> webUrl + "/join?error=invalid_invite";
            case "alpha_full" -> webUrl + "/join?error=alpha_full";
            default -> webUrl + "/login?error";
        };
    }

    /**
     * The Twitch login of the member whose link failed: the session's, which the link ran on top of (the security
     * context of the request itself is already cleared when a login fails).
     */
    private static String sessionLogin(HttpSession session) {
        if (session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                        instanceof SecurityContext context
                && context.getAuthentication() != null
                && context.getAuthentication().getPrincipal() instanceof CatapultOAuth2User user
                && user.getUserAccount() != null) {
            return user.getUserAccount().getTwitchUsername();
        }
        return null;
    }
}
