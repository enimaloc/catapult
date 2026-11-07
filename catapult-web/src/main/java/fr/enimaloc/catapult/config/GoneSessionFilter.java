package fr.enimaloc.catapult.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ends quietly a stream whose session went away while it was open. A stream's async dispatch saves the
 * session it started with; once that session is logged out, expired or under a new id (impersonation), it is
 * no longer in Redis and {@code RedisSessionRepository} throws. Nothing is left to save and the stream's
 * response is already sent: let through, the exception would only become a 500 and an error dispatch on it.
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
public class GoneSessionFilter extends OncePerRequestFilter {
    /** {@code RedisSessionRepository#save}'s message: the exception has no type of its own. */
    static final String SESSION_GONE = "Session was invalidated";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IllegalStateException e) {
            if (request.getDispatcherType() != DispatcherType.ASYNC || !SESSION_GONE.equals(e.getMessage())) throw e;
            logger.debug("A stream's session went away while it was open");
        }
    }
}
