package fr.enimaloc.catapult.ws;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ChannelWebSocketConfig implements WebSocketConfigurer {
    private final ChannelWebSocketHandler channelWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(channelWebSocketHandler, "/ws/channel/*")
                .addInterceptors(new UsernameHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    /**
     * Spring's plain {@link WebSocketConfigurer} has no {@code @PathVariable} support, so the
     * username is pulled from the handshake URI itself and stashed as a session attribute —
     * {@link ChannelWebSocketHandler} reads it back via {@link ChannelWebSocketHandler#USERNAME_ATTRIBUTE}.
     */
    private static class UsernameHandshakeInterceptor implements HandshakeInterceptor {
        private static final Pattern USERNAME_PATTERN = Pattern.compile("/ws/channel/([^/]+)/?$");

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                        WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String path = request instanceof ServletServerHttpRequest servletRequest
                    ? ((HttpServletRequest) servletRequest.getServletRequest()).getRequestURI()
                    : request.getURI().getPath();
            Matcher matcher = USERNAME_PATTERN.matcher(path);
            if (!matcher.find()) {
                return false;
            }
            attributes.put(ChannelWebSocketHandler.USERNAME_ATTRIBUTE, matcher.group(1));
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
