package fr.enimaloc.catapult.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Captures the ?invite=CODE query parameter from /oauth2/authorization/twitch
 * and stores it in the server-side session so CatapultOAuth2UserService can
 * read it during the OAuth callback. Same pattern as the bot-link-pending flow.
 */
@Slf4j
@Component
@Order(1)
public class InviteCodeRelayFilter extends OncePerRequestFilter {

    static final String SESSION_KEY = "invite-code";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if ("/oauth2/authorization/twitch".equals(request.getRequestURI())) {
            String invite = request.getParameter("invite");
            log.info("OAuth2 initiation — URI={}, invite param={}", request.getRequestURI(), invite);
            if (invite != null && !invite.isBlank()) {
                String sessionId = request.getSession(true).getId();
                request.getSession(true).setAttribute(SESSION_KEY, invite.trim().toUpperCase());
                log.info("Stored invite code '{}' in session {} for OAuth flow", invite.trim().toUpperCase(), sessionId);
            }
        }
        chain.doFilter(request, response);
    }
}
