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
            String jwt = session != null ? (String) session.getAttribute(ApiClient.SESSION_JWT_KEY) : null;

            if (jwt != null) {
                try {
                    CatapultWebUser user = validateWithApi(jwt);
                    if (user != null && user.isEnabled()) {
                        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (Exception e) {
                    log.debug("JWT validation failed, clearing session token: {}", e.getMessage());
                    if (session != null) {
                        session.removeAttribute(ApiClient.SESSION_JWT_KEY);
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    @SuppressWarnings("unchecked")
    private CatapultWebUser validateWithApi(String jwt) {
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
