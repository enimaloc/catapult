package fr.enimaloc.catapult.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Under the dev profile, names the app on every response ({@code Blinder-App: catapult}). Set before
 * anything else runs, so the security chain's refusals and the error pages carry it too.
 */
@Component
@Profile("dev")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevAppHeaderFilter extends OncePerRequestFilter {
    static final String HEADER = "Blinder-App";
    static final String VALUE = "catapult";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, VALUE);
        chain.doFilter(request, response);
    }
}
