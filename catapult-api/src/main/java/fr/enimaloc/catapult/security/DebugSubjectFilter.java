package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.service.debug.DebugContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs each API request as the member it is for (see {@link DebugContext}): the JWT subject (or the owner of the
 * personal API token on the custom bindings API), or, for an admin's request on a route about one member
 * ({@code /api/internal/admin/members/{id}[/…]}), that member, so what an admin does to a member is attributed to the member.
 * "An admin" is {@link Authz#isAdmin}, the {@code /api/internal/admin/**} access rule; anyone else on that route (who gets a
 * 403) stays attributed to themself. Placed after the bearer-token authentication; requests with neither run without
 * a subject. Async dispatches (SSE) are not filtered and so stay unattributed. A channel's Twitch category changed by
 * someone else (a moderator's or admin's edit, an admin game mapping) is still attributed to the channel's owner, by
 * the code that changes it.
 */
public class DebugSubjectFilter extends OncePerRequestFilter {

    private static final Pattern ADMIN_MEMBER_ROUTE = Pattern.compile("^/api/internal/admin/members/([^/]+)(?:/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> subject = subjectOf(request);
        if (subject.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Exception failure = DebugContext.callAs(subject.get(), () -> {
            try {
                chain.doFilter(request, response);
                return null;
            } catch (IOException | ServletException e) {
                return e;
            }
        });
        if (failure instanceof IOException e) {
            throw e;
        }
        if (failure instanceof ServletException e) {
            throw e;
        }
    }

    static Optional<UUID> subjectOf(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher adminRoute = ADMIN_MEMBER_ROUTE.matcher(path);
        if (adminRoute.matches() && Authz.isAdmin(auth, request)) {
            Optional<UUID> member = parse(adminRoute.group(1));
            if (member.isPresent()) {
                return member;
            }
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            return parse(jwt.getToken().getSubject());
        }
        // The custom bindings API: authenticated by the member's personal API token, its principal is their id
        if (auth != null && auth.getPrincipal() instanceof UUID member && Authz.isPersonalToken(auth)) {
            return Optional.of(member);
        }
        return Optional.empty();
    }

    private static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
