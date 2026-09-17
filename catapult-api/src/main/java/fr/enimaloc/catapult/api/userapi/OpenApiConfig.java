package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Pins the OpenAPI "servers" entry (and so Swagger UI's "Try it out" target and the base URL
 * shown in /v3/api-docs) to {@code app.base-url} instead of springdoc's default of deriving it
 * from the incoming request's Host header — the app sits behind an nginx router forwarding
 * multiple hostnames/paths, so the request-derived guess doesn't reliably match the public URL.
 *
 * <p>Also declares a Bearer JWT security scheme, purely for documentation — every endpoint here is
 * public (see each controller's permitAll security rule), the JWT isn't required by any of them.
 * The actual mechanism that lets Swagger UI's "uuid" examples reflect the caller's own data is
 * {@link SwaggerUiJwtTransformer} (attaches a JWT to every request, spec fetch included) plus
 * {@link ExampleUuidCustomizer} (resolves that caller and fills the example) — this scheme alone
 * wouldn't be enough, since Swagger UI's built-in "Authorize" dialog never applies to the request
 * that generates the spec in the first place.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().servers(List.of(new Server().url(baseUrl)));
    }

    /**
     * Defense-in-depth alongside {@link SwaggerUiJwtTransformer}'s use of a URL fragment (never
     * sent to the server) rather than a query param for the one-time JWT link: a browser that,
     * despite that, still ends up navigating away from a Swagger UI page carrying a token in its
     * URL won't leak it to the next origin via Referer.
     */
    @Bean
    public FilterRegistrationBean<Filter> swaggerUiReferrerPolicyFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
            ((jakarta.servlet.http.HttpServletResponse) response).setHeader("Referrer-Policy", "no-referrer");
            chain.doFilter(request, response);
        });
        registration.addUrlPatterns("/swagger-ui/*", "/api/v3/api-docs", "/api/v3/api-docs/*");
        return registration;
    }
}
