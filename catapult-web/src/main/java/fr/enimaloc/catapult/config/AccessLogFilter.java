package fr.enimaloc.catapult.config;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Logs every HTTP request once it is answered: method, path, status, duration and client IP
 * (the real one behind nginx, through {@code server.forward-headers-strategy}). The query string
 * is left out, since it can carry the one-time login code. Health checks are skipped: docker
 * hits them every 10s. Long-lived responses (SSE) are logged when they close. Static assets
 * (css, js, _static) are logged at DEBUG only.
 * <p>
 * Under the dev profile, each line is followed by the whole exchange the way {@code curl -v}
 * shows it.
 * <p>
 * Logged under the {@code access} logger, so {@code logging.level.access=WARN} silences it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class AccessLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("access");
    /** Headers whose value never reaches the logs, even in dev. */
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "cookie", "set-cookie", "x-xsrf-token");

    private final Environment environment;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            // Not answered yet: the error page that follows will be a 500.
            log(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, System.nanoTime() - start);
            throw e;
        }
        if (request.isAsyncStarted()) {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) { log(request, response, response.getStatus(), System.nanoTime() - start); }
                @Override public void onTimeout(AsyncEvent event) { /* onComplete follows */ }
                @Override public void onError(AsyncEvent event) { /* onComplete follows */ }
                @Override public void onStartAsync(AsyncEvent event) { /* nothing yet */ }
            });
        } else {
            log(request, response, response.getStatus(), System.nanoTime() - start);
        }
    }

    private void log(HttpServletRequest request, HttpServletResponse response, int status, long duration) {
        boolean compRoute = request.getRequestURI().matches("^/(css|js|_static)/.*");
        Level level = compRoute ? Level.DEBUG : Level.INFO;
        if (log.isEnabledForLevel(level)) {
            log.atLevel(level)
                    .log("{} {} {} {}ms {}", request.getMethod(), request.getRequestURI(), status,
                            duration / 1_000_000, request.getRemoteAddr());
            moreLog(level, request, response, status);
        }
    }

    /**
     * In dev, the exchange the way {@code curl -v} shows it: the request line (query string
     * included) and headers ({@code >}), then the status line and response headers ({@code <}).
     * Credentials are masked all the same.
     */
    private void moreLog(Level level, HttpServletRequest request, HttpServletResponse response, int status) {
        if (!environment.matchesProfiles("dev")) return;
        StringBuilder out = new StringBuilder("* Connected from ").append(request.getRemoteAddr())
                .append(':').append(request.getRemotePort()).append('\n');
        String query = request.getQueryString();
        out.append("> ").append(request.getMethod()).append(' ').append(request.getRequestURI())
                .append(query == null ? "" : "?" + query).append(' ').append(request.getProtocol()).append('\n');
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) {
                out.append("> ").append(name).append(": ").append(mask(name, value)).append('\n');
            }
        }
        out.append(">\n< ").append(request.getProtocol()).append(' ').append(status).append('\n');
        for (String name : response.getHeaderNames()) {
            for (String value : response.getHeaders(name)) {
                out.append("< ").append(name).append(": ").append(mask(name, value)).append('\n');
            }
        }
        out.append('<');
        log.atLevel(level).log("\n{}", out);
    }

    private static String mask(String header, String value) {
        return SENSITIVE_HEADERS.contains(header.toLowerCase(Locale.ROOT)) ? "***" : value;
    }
}
