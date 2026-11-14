package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Restores the Twitch login of catapult-api's own session when a secondary link (Steam, Xbox, Battle.net) starts.
 *
 * <p>The link's callback finds the user in that session ({@link CatapultOAuth2UserService}), which lives in memory:
 * a restart of catapult-api, or another replica, loses it while catapult-web still shows the user logged in. So on
 * {@code /oauth2/authorization/<provider>}, catapult-web relays the user's JWT as a bearer token, checked by the same
 * {@link JwtDecoder} as {@code /api/**}. The user it names replaces the session's, if any: a stale session may still
 * hold the account logged in before, and the link must go to the one catapult-web shows.
 *
 * <p>Must run BEFORE Spring Security's chain, which loads the session's context and redirects to the provider.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class LinkSessionRestoreFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/oauth2/authorization/";
    private static final String BEARER = "Bearer ";
    /** Carried by the JWT an admin gets to act as a member ({@link ImpersonationService}). */
    static final String IMPERSONATION_ROLE = "ROLE_PREVIOUS_ADMINISTRATOR";

    // Resolved on use: a servlet filter is picked up by every web test slice, which has neither
    private final ObjectProvider<JwtDecoder> jwtDecoder;
    private final ObjectProvider<UserAccountRepository> users;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Twitch is the login itself: nothing to restore before it
        return !uri.startsWith(PATH_PREFIX) || uri.equals(PATH_PREFIX + "twitch");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        bearerUser(request)
                .filter(user -> !user.getUserAccount().getId().equals(sessionUserId(request.getSession(false))))
                .ifPresent(user -> restore(request, user));
        chain.doFilter(request, response);
    }

    private static UUID sessionUserId(HttpSession session) {
        if (session != null
                && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                        instanceof SecurityContext context
                && context.getAuthentication() != null
                && context.getAuthentication().getPrincipal() instanceof CatapultOAuth2User user) {
            return user.getUserAccount().getId();
        }
        return null;
    }

    private Optional<CatapultOAuth2User> bearerUser(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        UserAccountRepository accounts = users.getIfAvailable();
        if (header == null || !header.startsWith(BEARER) || decoder == null || accounts == null) return Optional.empty();
        Jwt jwt;
        UUID id;
        try {
            jwt = decoder.decode(header.substring(BEARER.length()).trim());
            id = UUID.fromString(jwt.getSubject());
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            log.info("Secondary link started with an unusable token ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
        List<String> roles = Optional.ofNullable(jwt.getClaimAsStringList("roles")).orElse(List.of());
        if (roles.contains(IMPERSONATION_ROLE)) {
            // An admin acting as a member: their own provider account must never be linked to the member's
            log.info("Secondary link started while impersonating: no session restored");
            return Optional.empty();
        }
        return accounts.findById(id)
                .filter(account -> account.getStatus() == UserAccount.Status.ACTIVE)
                .map(account -> new CatapultOAuth2User(null, account, roles.contains("ROLE_ADMIN")));
    }

    private static void restore(HttpServletRequest request, CatapultOAuth2User user) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            request.getSession(true);
        } else {
            // A session that gains or changes its user gets a new id (session fixation)
            request.changeSessionId();
        }
        SecurityContext context = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        log.info("Restored the session of user {} for a secondary link", user.getUserAccount().getId());
    }
}
