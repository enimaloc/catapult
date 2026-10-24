package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates a request to {@code /api/v2/custom-bindings/**} by its {@code Authorization: Bearer <personal API
 * token>} header, and by nothing else: a JWT, a widget token or no header leaves it unauthenticated (401). The
 * principal is the member's account id ({@link UUID}).
 *
 * <p>Not a bean, on purpose: Spring Boot would register it for every request. ApiSecurityConfig adds it to the
 * custom bindings chain only.</p>
 */
public class PersonalTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "API_TOKEN";
    private static final String BEARER = "Bearer ";

    private final ObjectProvider<UserApiTokenService> tokens;

    public PersonalTokenAuthenticationFilter(ObjectProvider<UserApiTokenService> tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        bearer(request)
                .flatMap(token -> Optional.ofNullable(tokens.getIfAvailable()).flatMap(s -> s.authenticate(token)))
                .map(UserAccount::getId)
                .ifPresent(userId -> {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            userId, null, AuthorityUtils.createAuthorityList(AUTHORITY)));
                    SecurityContextHolder.setContext(context);
                });
        chain.doFilter(request, response);
    }

    private static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) return Optional.empty();
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
