package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.client.ApiClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the JWT stored in the HttpSession (key: "jwt"), calls catapult-api /api/auth/validate,
 * and populates the SecurityContext with a CatapultWebUser if valid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtSessionAuthFilter extends OncePerRequestFilter {

    private final ApiClient apiClient;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                refreshFromSession(session);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Validate the JWT currently stored on the session and install the
     * resulting principal on {@link SecurityContextHolder}. Clears the context
     * when no JWT is present or validation throws (so a stale token does not
     * keep flooding the API every request).
     *
     * <p>Exposed so callers that swap the session JWT mid-request — i.e.
     * {@code AdminImpersonateController} — can rebuild the SecurityContext in
     * the same request instead of relying on the next one to pick it up via
     * the filter chain.</p>
     */
    public void refreshFromSession(HttpSession session) {
        String jwt = (String) session.getAttribute(ApiClient.SESSION_JWT_KEY);
        if (jwt == null) {
            SecurityContextHolder.clearContext();
            return;
        }
        try {
            CatapultWebUser user = validateJwt(jwt);
            if (user != null && user.isEnabled()) {
                var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                SecurityContextHolder.clearContext();
            }
        } catch (Exception e) {
            log.debug("JWT validation failed, clearing session token: {}", e.getMessage());
            session.removeAttribute(ApiClient.SESSION_JWT_KEY);
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Validates a JWT against catapult-api and builds the resulting {@link CatapultWebUser}.
     * Exposed so other entry points that authenticate outside the servlet filter chain — i.e.
     * {@code HtmxWsDispatcher} bridging WS {@code mvc} actions — can build the same principal
     * instead of a bare id, which {@code @AuthenticationPrincipal CatapultWebUser} consumers
     * (e.g. {@code GlobalModelAdvice}) would otherwise silently see as {@code null}.
     */
    @SuppressWarnings("unchecked")
    public CatapultWebUser validateJwt(String jwt) {
        Map<?, ?> body = apiClient.get("/api/auth/validate", Map.class);
        if (body == null) return null;

        String deletionStr = (String) body.get("deletionRequestedAt");
        Instant deletionRequestedAt = deletionStr != null ? Instant.parse(deletionStr) : null;

        return new CatapultWebUser(
                UUID.fromString((String) body.get("id")),
                (String) body.get("twitchId"),
                (String) body.get("username"),
                (String) body.get("profileImageUrl"),
                (String) body.get("status"),
                (List<String>) body.get("roles"),
                deletionRequestedAt
        );
    }
}
