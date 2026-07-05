package fr.enimaloc.catapult.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes {@code hx-boost} navigation follow server-side redirects.
 *
 * <p>htmx does not follow HTTP 3xx responses on boosted requests — it honours
 * the {@code HX-Redirect} response header instead. So a boosted link/form that
 * hits a {@code redirect:/…} controller (e.g. {@code /app → /channels → /channels/{user}})
 * silently does nothing. This filter turns a {@code sendRedirect()} into
 * {@code 204 No Content + HX-Redirect: <location>} for requests carrying the
 * {@code HX-Request} header, so the browser navigates correctly.</p>
 *
 * <p>Only real HTTP requests pass through the servlet filter chain: the
 * {@code ws:*}/{@code mvc} WebSocket path dispatches internally
 * ({@code DispatcherServlet.service}) and is unaffected, and forms marked
 * {@code hx-boost="false"} (logout, impersonate) never send {@code HX-Request},
 * so they keep their full-page redirect.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HxBoostRedirectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void sendRedirect(String location) {
                if (isCommitted()) return;
                setStatus(SC_NO_CONTENT);
                setHeader("HX-Redirect", location);
            }
        });
    }
}
