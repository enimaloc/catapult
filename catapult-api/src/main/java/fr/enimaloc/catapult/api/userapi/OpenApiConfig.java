package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Pins the OpenAPI "servers" entry (and so Swagger UI's "Try it out" target and the base URL
 * shown in /v3/api-docs) to {@code app.base-url} instead of springdoc's default of deriving it
 * from the incoming request's Host header — the app sits behind an nginx router forwarding
 * multiple hostnames/paths, so the request-derived guess doesn't reliably match the public URL.
 */
@Configuration
public class OpenApiConfig {

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().servers(List.of(new Server().url(baseUrl)));
    }
}
