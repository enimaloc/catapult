package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.service.account.ApiTokenOwner;
import fr.enimaloc.catapult.service.account.UserApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * Authenticates a request to the personal-token API ({@code /api/me/**}, and {@code /api/v2/custom-bindings/**} until
 * it goes) by a member's personal API token, and by nothing else: a JWT, a widget token or no token leaves it
 * unauthenticated (401). The principal is the member's account id ({@link UUID}); the authority tells the token's
 * scope apart: {@value #AUTHORITY} for the full token, {@value #READ_AUTHORITY} for the read-only one.
 * <p>
 * The token comes from {@code Authorization: Bearer <token>}. Only when the request has no {@code Authorization}
 * header at all is the {@code token} parameter read instead, and there the read-only token alone counts: a full token
 * in a URL would leak a credential that can change things. An {@code Authorization} header that is not a valid token
 * is a 401 even beside a valid {@code token}.
 *
 * <p>Not a bean, on purpose: Spring Boot would register it for every request. ApiSecurityConfig adds it to the
 * personal-token chain only.</p>
 */
public class PersonalTokenAuthenticationFilter extends OncePerRequestFilter {

    /** Granted to the full token ({@link ApiTokenScope#FULL}). */
    public static final String AUTHORITY = "API_TOKEN";
    /** Granted to the read-only token ({@link ApiTokenScope#READ}). */
    public static final String READ_AUTHORITY = "API_TOKEN_READ";
    /** The request parameter that may carry the read-only token, for clients that cannot set a header. */
    public static final String QUERY_PARAMETER = "token";
    private static final String BEARER = "Bearer ";

    private final UserApiTokenService tokens;

    public PersonalTokenAuthenticationFilter(UserApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        owner(request).ifPresent(owner -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    owner.user().getId(), null, AuthorityUtils.createAuthorityList(authorityOf(owner.scope()))));
            SecurityContextHolder.setContext(context);
        });
        chain.doFilter(request, response);
    }

    /** The header's token if there is a header, else the {@code token} parameter's when it is the read-only one. */
    private Optional<ApiTokenOwner> owner(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            return bearer(request).flatMap(tokens::authenticate);
        }
        String query = request.getParameter(QUERY_PARAMETER);
        // A full token is refused here without even being looked up
        if (query == null || !query.startsWith(ApiTokenScope.READ.prefix())) return Optional.empty();
        return tokens.authenticate(query).filter(owner -> owner.scope() == ApiTokenScope.READ);
    }

    static String authorityOf(ApiTokenScope scope) {
        return switch (scope) {
            case FULL -> AUTHORITY;
            case READ -> READ_AUTHORITY;
        };
    }

    private static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) return Optional.empty();
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
