package fr.enimaloc.catapult.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * How long browsers keep the site's own static files. A versioned one (the templates name {@code /js}, {@code /css}
 * and {@code /img} through {@code @{...}}, which appends the content's hash: spa-&lt;md5&gt;.js) and {@code /_static}'s,
 * whose path names the library's version, never change: kept a year, without revalidating. Any other is revalidated
 * on each use (a 304 while it is unchanged). Set before the security chain's own, which would otherwise forbid any
 * caching. A failure is never kept: during a deploy, an instance still on the old files answers a new hash with a 404.
 */
@Component
public class StaticCacheFilter extends OncePerRequestFilter {
    static final String IMMUTABLE = "max-age=31536000, public, immutable";
    static final String REVALIDATE = "no-cache";
    private static final Pattern STATIC = Pattern.compile("^/(js|css|img|_static)/.*");
    private static final Pattern VERSIONED = Pattern.compile("^/_static/.*|.*-[0-9a-f]{32}\\.[A-Za-z0-9]+$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !STATIC.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL,
                VERSIONED.matcher(request.getRequestURI()).matches() ? IMMUTABLE : REVALIDATE);
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setStatus(int sc) {
                if (sc >= 400) noStore();
                super.setStatus(sc);
            }

            @Override
            public void sendError(int sc) throws IOException {
                noStore();
                super.sendError(sc);
            }

            @Override
            public void sendError(int sc, String msg) throws IOException {
                noStore();
                super.sendError(sc, msg);
            }

            private void noStore() {
                setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            }
        });
    }
}
