package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.account.ChannelAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * The one definition of the request-level access rules: who is a member, an admin, a loopback caller or a personal
 * API token holder. The filter chains and {@link DebugSubjectFilter} use these, so the rules cannot drift apart.
 * A relayed client ({@code X-Forwarded-For}) is never loopback: with {@code server.forward-headers-strategy=native}
 * the server (Tomcat's {@code RemoteIpValve}) replaces the remote address with the client it resolves from
 * {@code X-Forwarded-For}, so a request relayed by a local proxy carries that client's address, not the proxy's.
 * <p>
 * It also decides who reaches a channel, for {@code @PreAuthorize("@authz.canAccessChannel(#username)")} and
 * {@code @PreAuthorize("@authz.ownsChannel(#username)")}. Those keep the order every channel route answers in: 401 for
 * a caller without a JWT or with an unknown account, then 404 for an unknown channel (both thrown as
 * {@link org.springframework.web.server.ResponseStatusException}, which SpEL rethrows as is), then {@code false},
 * a 403, for a caller who may not see it.
 */
@Component("authz")
public class Authz {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private static final IpAddressMatcher LOOPBACK_V4 = new IpAddressMatcher("127.0.0.1");
    private static final IpAddressMatcher LOOPBACK_V6 = new IpAddressMatcher("::1");
    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private final ApiUserResolver users;
    private final ChannelAccessService channels;

    public Authz(ApiUserResolver users, ChannelAccessService channels) {
        this.users = users;
        this.channels = channels;
    }

    /** The current caller is the channel's owner or one of its Twitch moderators. 401, then 404, before deciding. */
    public boolean canAccessChannel(String username) {
        UserAccount viewer = users.viewer(currentJwt());
        return canAccess(viewer, users.channel(username));
    }

    /** The current caller is the channel's owner. 401, then 404, before deciding; never asks Twitch. */
    public boolean ownsChannel(String username) {
        UserAccount viewer = users.viewer(currentJwt());
        return viewer.getId().equals(users.channel(username).getId());
    }

    /** {@code viewer} is {@code channel}'s owner or one of its Twitch moderators ({@link ChannelAccessService}). */
    public boolean canAccess(UserAccount viewer, UserAccount channel) {
        return channels.canAccess(viewer, channel);
    }

    /** The current request's JWT, or {@code null} (a 401 from {@link ApiUserResolver#viewer}) when it has none. */
    private static Jwt currentJwt() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token
                ? token.getToken()
                : null;
    }

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
