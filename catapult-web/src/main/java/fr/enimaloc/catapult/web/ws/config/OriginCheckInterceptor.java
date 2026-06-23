package fr.enimaloc.catapult.web.ws.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * Belt-and-braces {@code Origin}-header check at WS handshake.
 *
 * <p>Spring's {@code setAllowedOriginPatterns} already filters origins, but a
 * dedicated interceptor lets us log rejections explicitly and centralise the
 * policy decision (notably for the dev wildcard).</p>
 */
@Slf4j
@Component
public class OriginCheckInterceptor implements HandshakeInterceptor {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<String> allowedPatterns;

    public OriginCheckInterceptor(@Value("${catapult.ws.allowed-origin-patterns:*}") String[] allowedOriginPatterns) {
        this.allowedPatterns = List.of(allowedOriginPatterns);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String origin = request.getHeaders().getFirst("Origin");
        // Same-origin requests may omit the Origin header (e.g. server-to-server),
        // accept those — the front always sends it via window.location.
        if (origin == null) return true;
        if (matches(origin)) return true;

        log.warn("ws handshake rejected: origin={} not in allowlist {}", origin, allowedPatterns);
        if (response instanceof ServletServerHttpResponse servlet) {
            servlet.getServletResponse().setStatus(HttpStatus.FORBIDDEN.value());
        } else {
            response.setStatusCode(HttpStatus.FORBIDDEN);
        }
        return false;
    }

    boolean matches(String origin) {
        for (String pattern : allowedPatterns) {
            if ("*".equals(pattern)) return true;
            if (pattern.equals(origin)) return true;
            if (MATCHER.match(pattern, origin)) return true;
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }
}
