package fr.enimaloc.catapult.web.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableScheduling
@RequiredArgsConstructor
public class WsConfig implements WebSocketConfigurer {

    private final WsHub hub;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/ws").setAllowedOriginPatterns("*");
        // NOTE: origin check tightened in Phase 3 with HandshakeInterceptor.
        // For now, app is dev-only on this branch.
    }

    @Bean
    public ServletServerContainerFactoryBean wsContainer() {
        var c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(16 * 1024);          // 16 KB
        c.setMaxSessionIdleTimeout(120_000L);              // 2 min idle
        return c;
    }
}
