package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
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
 * carry no requirement. Swagger UI showing a requirement as a padlock alone, the header ({@code info}) tells how to send
 * a token, with curl examples, and each {@code /api/me} operation's description names the token it takes.
 */
@Configuration
public class OpenApiConfig {

    /** The scheme of the {@code Authorization: Bearer} header, accepting both personal tokens. */
    public static final String PERSONAL_TOKEN = "personalToken";
    /** The scheme of the {@code ?token=} query parameter, accepting the read-only token alone. */
    public static final String READ_TOKEN = "readToken";

    private static final String ME_PREFIX = "/api/me";
    /** What an opened /api/me operation says of its token, Swagger UI showing its requirement as a padlock only. */
    private static final String READ_NOTE = "**Token:** `Authorization: Bearer cat_…` or `Authorization: Bearer "
            + "catr_…`, or, without that header, `?token=catr_…` (the read-only token only).";
    private static final String WRITE_NOTE = "**Token:** `Authorization: Bearer cat_…` (the full token only; the "
            + "read-only one and the query string are refused).";

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Catapult API").version("2").description(description()))
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

    /** The page's header: how to send a personal token, the scheme padlocks alone not saying it. */
    private String description() {
        String url = baseUrl.replaceAll("/+$", "");
        return """
                Your channel's game, bindings and custom bindings, and public per-platform game detail.

                ## Authentication

                The `/api/me` routes take a personal API token, generated from the account settings (or from \
                the keys card on this page). The `/api/games` routes are public and take none.

                | Token | Prefix | Opens |
                |---|---|---|
                | Full | `cat_…` | every `/api/me` route, reads and changes |
                | Read-only | `catr_…` | the `/api/me` `GET` routes only |

                Send it in the `Authorization` header:

                ```
                curl -H "Authorization: Bearer cat_…" -X DELETE %1$s/api/me/custom-bindings/active
                curl -H "Authorization: Bearer catr_…" %1$s/api/me/game
                ```

                Where no header can be set (a URL pasted into an OBS browser source, a chat bot), a `GET` takes \
                the read-only token in the query string instead, `?token=catr_…`; the full token is refused there:

                ```
                curl "%1$s/api/me/game/summary?token=catr_…"
                ```

                Here, **Authorize** keeps a token for **Try it out**: `personalToken` for the header, \
                `readToken` for the query string.
                """.formatted(url);
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
                        if (operation != item.getGet()) {
                            appendNote(operation, WRITE_NOTE);
                        }
                    }
                    if (item.getGet() != null) {
                        item.getGet().addSecurityItem(new SecurityRequirement().addList(READ_TOKEN));
                        appendNote(item.getGet(), READ_NOTE);
                    }
                }
            });
        };
    }

    private static void appendNote(Operation operation, String note) {
        String description = operation.getDescription();
        operation.setDescription(description == null || description.isBlank() ? note : description + "\n\n" + note);
    }
}
