package fr.enimaloc.catapult.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Captures the ?invite=CODE query parameter from /oauth2/authorization/twitch
 * and persists it both in the session AND in a short-lived HttpOnly cookie so
 * CatapultOAuth2UserService can read it during the OAuth callback.
 *
 * Must run BEFORE Spring Security's OAuth2AuthorizationRequestRedirectFilter,
 * which otherwise intercepts /oauth2/authorization/** and short-circuits the chain.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InviteCodeRelayFilter extends OncePerRequestFilter {

    static final String SESSION_KEY = "invite-code";
    static final String COOKIE_NAME = "catapult_invite";
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
                ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, normalized)
                    .httpOnly(true)
                    .secure(request.isSecure())
                    .path("/")
                    .maxAge(Duration.ofSeconds(COOKIE_MAX_AGE_SECONDS))
                    .sameSite("Lax")
                    .build();
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
                log.info("Stored invite code '{}' in session + cookie for OAuth flow", normalized);
            }
        }
        chain.doFilter(request, response);
    }
}
