package fr.enimaloc.catapult.config;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs every HTTP request once it is answered: method, path, status, duration and client IP
 * (the real one behind nginx, through {@code server.forward-headers-strategy}). The query string
 * is left out, since it can carry the one-time login code. Health checks are skipped: docker
 * hits them every 10s. Long-lived responses (SSE) are logged when they close.
 * <p>
 * Logged under the {@code access} logger, so {@code logging.level.access=WARN} silences it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("access");

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
            log(request, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, start);
            throw e;
        }
        if (request.isAsyncStarted()) {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) { log(request, response.getStatus(), start); }
                @Override public void onTimeout(AsyncEvent event) { /* onComplete follows */ }
                @Override public void onError(AsyncEvent event) { /* onComplete follows */ }
                @Override public void onStartAsync(AsyncEvent event) { /* nothing yet */ }
            });
        } else {
            log(request, response.getStatus(), start);
        }
    }

    private static void log(HttpServletRequest request, int status, long start) {
        if (log.isInfoEnabled()) {
            log.info("{} {} {} {}ms {}", request.getMethod(), request.getRequestURI(), status,
                    (System.nanoTime() - start) / 1_000_000, request.getRemoteAddr());
        }
    }
}
