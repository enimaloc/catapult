package fr.enimaloc.catapult.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * The one definition of the request-level access rules: who is a member, an admin, a loopback caller or a personal
 * API token holder. The filter chains and {@link DebugSubjectFilter} use these, so the rules cannot drift apart.
 * A relayed client ({@code X-Forwarded-For}) is never loopback: the remote address is the one of the connection.
 */
@Component("authz")
public class Authz {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private static final IpAddressMatcher LOOPBACK_V4 = new IpAddressMatcher("127.0.0.1");
    private static final IpAddressMatcher LOOPBACK_V6 = new IpAddressMatcher("::1");
    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    /** The connection comes from {@code 127.0.0.1} or {@code ::1}. */
    public static boolean isLoopback(HttpServletRequest request) {
        return LOOPBACK_V4.matches(request) || LOOPBACK_V6.matches(request);
    }

    /** Authenticated, and not anonymous. */
    public static boolean isMember(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !TRUST.isAnonymous(auth);
    }

    /** {@code ROLE_ADMIN}, or a loopback caller. */
    public static boolean isAdmin(Authentication auth, HttpServletRequest request) {
        return hasAuthority(auth, ADMIN_AUTHORITY) || isLoopback(request);
    }

    /** Authenticated by the personal API token. */
    public static boolean isPersonalToken(Authentication auth) {
        return hasAuthority(auth, PersonalTokenAuthenticationFilter.AUTHORITY);
    }

    public static AuthorizationManager<RequestAuthorizationContext> admin() {
        return (auth, ctx) -> new AuthorizationDecision(isAdmin(auth.get(), ctx.getRequest()));
    }

    public static AuthorizationManager<RequestAuthorizationContext> memberOrLoopback() {
        return (auth, ctx) -> new AuthorizationDecision(isMember(auth.get()) || isLoopback(ctx.getRequest()));
    }

    public static AuthorizationManager<RequestAuthorizationContext> personalToken() {
        return (auth, ctx) -> new AuthorizationDecision(isPersonalToken(auth.get()));
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
