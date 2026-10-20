package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.controller.SseStreams;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Keeps a session logged in only as long as its catapult-api JWT holds: once that JWT has expired (read
 * here, from its own {@code exp}), or catapult-api refuses it (asked at most every {@link #REVALIDATE}
 * per session, the answer kept in the session: an account deleted, banned or logged out elsewhere), the
 * session is emptied and its streams close on every instance. catapult-api unreachable logs no one out.
 * Static files are never checked.
 */
@Slf4j
public class SessionJwtFilter extends OncePerRequestFilter {
    static final Duration REVALIDATE = Duration.ofMinutes(5);
    /** When catapult-api last accepted the session's JWT, in epoch milliseconds (a string, as every attribute). */
    static final String CHECKED_AT_KEY = "jwtCheckedAt";
    private static final String[] STATIC = {"/css/", "/js/", "/img/", "/fonts/", "/webjars/", "/favicon", "/assets/"};

    private final Supplier<ApiService> apiService;
    private final Supplier<SseStreams> sse;
    private final Clock clock;

    public SessionJwtFilter(Supplier<ApiService> apiService, Supplier<SseStreams> sse, Clock clock) {
        this.apiService = apiService;
        this.sse = sse;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (String prefix : STATIC) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        try {
            if (session != null && session.getAttribute(ApiClient.SESSION_JWT_KEY) instanceof String jwt && !holds(session, jwt)) {
                SseStreams streams = sse.get();
                if (streams != null) streams.closeSession(session.getId());
                session.invalidate();
            }
        } catch (IllegalStateException e) {
            // invalidated meanwhile
        }
        chain.doFilter(request, response);
    }

    /** Whether {@code session}'s {@code jwt} still holds: not expired, and not refused when last asked. */
    private boolean holds(HttpSession session, String jwt) {
        Instant now = clock.instant();
        Instant expiresAt = ApiClient.expiresAt(jwt);
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            log.debug("A session's JWT expired, it is logged out");
            return false;
        }
        if (!due(session, now)) return true;
        ApiService api = apiService.get();
        int status = api == null ? 0 : api.validateSession();
        if (status == 401 || status == 403) {
            log.debug("catapult-api refused a session's JWT ({}), it is logged out", status);
            return false;
        }
        if (status >= 200 && status < 300) session.setAttribute(CHECKED_AT_KEY, Long.toString(now.toEpochMilli()));
        return true;
    }

    private static boolean due(HttpSession session, Instant now) {
        if (!(session.getAttribute(CHECKED_AT_KEY) instanceof String checkedAt)) return true;
        try {
            return !now.isBefore(Instant.ofEpochMilli(Long.parseLong(checkedAt)).plus(REVALIDATE));
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
