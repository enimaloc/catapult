package fr.enimaloc.catapult.relay;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The relayed public API ({@code /api/me/**} by personal token, {@code /api/games/**} for anyone) is open to a page
 * of any origin (Twitchat runs in the browser): the personal token, in {@code Authorization} or the read-only one in
 * {@code ?token=}, is its only credential, so the answer allows no credentials (no cookie is ever sent along) and any
 * origin is safe. catapult-web answers the preflight itself, unauthenticated, before the relay; the relay then keeps
 * these headers over catapult-api's own. No other path gets CORS here.
 */
@Configuration
@Profile("!mock")
class PublicApiCorsConfig implements WebMvcConfigurer {

    /** The {@code /api/me} paths relayed (see {@link ApiRelayController#PATHS}). */
    static final String[] ME_PATHS = {
            "/api/me/game", "/api/me/game/**", "/api/me/bindings/**",
            "/api/me/custom-bindings", "/api/me/custom-bindings/**",
    };
    static final String GAMES_PATH = "/api/games/**";
    /** The request header choosing the API version, next to the credential and the body's type. */
    static final String[] ALLOWED_HEADERS = {"Authorization", "Content-Type", "X-API-Version"};

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        for (String path : ME_PATHS) {
            registry.addMapping(path)
                    .allowedOrigins("*")
                    .allowedMethods("GET", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders(ALLOWED_HEADERS)
                    .allowCredentials(false);
        }
        registry.addMapping(GAMES_PATH)
                .allowedOrigins("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders(ALLOWED_HEADERS)
                .allowCredentials(false);
    }
}
