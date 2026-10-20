package fr.enimaloc.catapult.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Captures the ?invite=CODE query parameter from /oauth2/authorization/twitch
 * and persists it both in the session AND in a short-lived HttpOnly cookie so
 * CatapultOAuth2UserService can read it during the OAuth callback.
 *
 * <p>catapult-web's {@code ?web_state=} goes the same way, for TwitchLoginSuccessHandler to send it
 * back as {@code state} to catapult-web's {@code /auth/callback}, which only completes a login its own
 * session started (login CSRF). The cookie keeps it through the failure handler's retry, which drops
 * the session.
 *
 * <p>Must run BEFORE Spring Security's OAuth2AuthorizationRequestRedirectFilter,
 * which otherwise intercepts /oauth2/authorization/** and short-circuits the chain.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InviteCodeRelayFilter extends OncePerRequestFilter {

    static final String SESSION_KEY = "invite-code";
    static final String COOKIE_NAME = "catapult_invite";
    static final String WEB_STATE_SESSION_KEY = "web-state";
    static final String WEB_STATE_COOKIE_NAME = "catapult_web_state";
    private static final int COOKIE_MAX_AGE_SECONDS = 600;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("/oauth2/authorization/twitch".equals(request.getRequestURI())) {
            String invite = request.getParameter("invite");
            log.info("OAuth2 initiation — URI={}, invite param={}", request.getRequestURI(), invite);
            if (invite != null && !invite.isBlank()) {
                String normalized = invite.trim().toUpperCase();
                request.getSession(true).setAttribute(SESSION_KEY, normalized);
                response.addHeader(HttpHeaders.SET_COOKIE,
                    cookie(request, COOKIE_NAME, normalized, Duration.ofSeconds(COOKIE_MAX_AGE_SECONDS)).toString());
                log.info("Stored invite code '{}' in session + cookie for OAuth flow", normalized);
            }
            String webState = request.getParameter("web_state");
            if (webState != null && !webState.isBlank()) {
                request.getSession(true).setAttribute(WEB_STATE_SESSION_KEY, webState);
                response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, WEB_STATE_COOKIE_NAME, webState,
                    Duration.ofSeconds(COOKIE_MAX_AGE_SECONDS)).toString());
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * The invite code relayed to the current request's OAuth callback — from the session, else
     * from the cookie (the session may not survive the round trip to Twitch).
     */
    static Optional<String> pendingCode() {
        try {
            HttpServletRequest request = currentRequest().getRequest();
            HttpSession session = request.getSession(false);
            String fromSession = session != null ? (String) session.getAttribute(SESSION_KEY) : null;
            String fromCookie = readCookie(request, COOKIE_NAME);
            log.info("Pending invite code — sessionId={}, fromSession={}, fromCookie={}",
                session != null ? session.getId() : "null", fromSession, fromCookie);
            return Optional.ofNullable(fromSession != null ? fromSession : fromCookie);
        } catch (IllegalStateException e) {
            log.warn("Pending invite code — no request context: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Forgets the relayed invite code once redeemed: session attribute and cookie. */
    static void clearPendingCode() {
        clear(SESSION_KEY, COOKIE_NAME);
    }

    /**
     * catapult-web's state relayed to {@code request}'s OAuth callback — from the session, else from the
     * cookie (the failure handler's retry drops the session).
     */
    static Optional<String> pendingWebState(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String fromSession = session != null ? (String) session.getAttribute(WEB_STATE_SESSION_KEY) : null;
        return Optional.ofNullable(fromSession != null ? fromSession : readCookie(request, WEB_STATE_COOKIE_NAME));
    }

    /** Forgets catapult-web's relayed state once sent back or the login given up: session attribute and cookie. */
    static void clearPendingWebState(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(WEB_STATE_SESSION_KEY);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, WEB_STATE_COOKIE_NAME, "", Duration.ZERO).toString());
    }

    private static void clear(String sessionKey, String cookieName) {
        try {
            ServletRequestAttributes attrs = currentRequest();
            HttpServletRequest request = attrs.getRequest();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(sessionKey);
            }
            attrs.getResponse().addHeader(HttpHeaders.SET_COOKIE, cookie(request, cookieName, "", Duration.ZERO).toString());
        } catch (IllegalStateException ignored) {
            // No request bound to this thread: nothing to clear
        }
    }

    private static ServletRequestAttributes currentRequest() {
        return (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    }

    private static ResponseCookie cookie(HttpServletRequest request, String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(request.isSecure())
            .path("/")
            .maxAge(maxAge)
            .sameSite("Lax")
            .build();
    }

    private static String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
