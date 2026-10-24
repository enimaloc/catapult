package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.service.debug.DebugContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs each API request as the member it is for (see {@link DebugContext}): the JWT subject, or, for an admin's
 * request on a route about one member ({@code /api/admin/members/{id}[/…]}), that member, so what an admin does to a
 * member is attributed to the member. "An admin" mirrors the {@code /api/admin/**} access rule: {@code ROLE_ADMIN} or
 * a loopback address; anyone else on that route (who gets a 403) stays attributed to themself. Placed after the bearer-token authentication; requests with neither run without a
 * subject. Async dispatches (SSE) are not filtered and so stay unattributed.
 */
public class DebugSubjectFilter extends OncePerRequestFilter {

    private static final Pattern ADMIN_MEMBER_ROUTE = Pattern.compile("^/api/admin/members/([^/]+)(?:/.*)?$");
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private static final IpAddressMatcher LOOPBACK_V4 = new IpAddressMatcher("127.0.0.1");
    private static final IpAddressMatcher LOOPBACK_V6 = new IpAddressMatcher("::1");

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
        if (adminRoute.matches() && isAdmin(auth, request)) {
            Optional<UUID> member = parse(adminRoute.group(1));
            if (member.isPresent()) {
                return member;
            }
        }
        if (auth instanceof JwtAuthenticationToken jwt) {
            return parse(jwt.getToken().getSubject());
        }
        return Optional.empty();
    }

    private static boolean isAdmin(Authentication auth, HttpServletRequest request) {
        boolean adminRole = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ADMIN_AUTHORITY.equals(a.getAuthority()));
        return adminRole || LOOPBACK_V4.matches(request) || LOOPBACK_V6.matches(request);
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
