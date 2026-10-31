package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Pins the OpenAPI "servers" entry (and so Swagger UI's "Try it out" target and the base URL
 * shown in /v3/api-docs) to {@code app.base-url} instead of springdoc's default of deriving it
 * from the incoming request's Host header — the app sits behind catapult-web's relay and a TLS proxy forwarding
 * multiple hostnames/paths, so the request-derived guess doesn't reliably match the public URL.
 *
 * <p>Declares the two ways to present a personal API token, and requires either of them on every
 * {@code /api/me} {@code GET}, the bearer one on its other operations; the {@code /api/games} routes are public and
 * carry no requirement.
 */
@Configuration
public class OpenApiConfig {

    /** The scheme of the {@code Authorization: Bearer} header, accepting both personal tokens. */
    public static final String PERSONAL_TOKEN = "personalToken";
    /** The scheme of the {@code ?token=} query parameter, accepting the read-only token alone. */
    public static final String READ_TOKEN = "readToken";

    private static final String ME_PREFIX = "/api/me";

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .servers(List.of(new Server().url(baseUrl)))
                .components(new Components()
                        .addSecuritySchemes(PERSONAL_TOKEN, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("A personal API token from the account settings, in the "
                                        + "Authorization header: the full token (cat_…) reads and changes "
                                        + "everything, the read-only token (catr_…) only the GET routes."))
                        .addSecuritySchemes(READ_TOKEN, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.QUERY)
                                .name("token")
                                .description("The read-only personal token (catr_…) in the query string, for a "
                                        + "URL that cannot carry a header. GET routes only, and only when the "
                                        + "request has no Authorization header; the full token is refused here.")));
    }

    /**
     * The bearer scheme opens an {@code /api/me} operation; the {@code ?token=} one opens its {@code GET}s too, the
     * read-only token opening no write.
     */
    @Bean
    public OpenApiCustomizer personalTokenRequirement() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (path.equals(ME_PREFIX) || path.startsWith(ME_PREFIX + "/")) {
                    for (Operation operation : item.readOperations()) {
                        operation.addSecurityItem(new SecurityRequirement().addList(PERSONAL_TOKEN));
                    }
                    if (item.getGet() != null) {
                        item.getGet().addSecurityItem(new SecurityRequirement().addList(READ_TOKEN));
                    }
                }
            });
        };
    }
}
