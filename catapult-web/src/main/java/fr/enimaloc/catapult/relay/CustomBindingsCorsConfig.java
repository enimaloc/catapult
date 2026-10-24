package fr.enimaloc.catapult.relay;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The custom bindings API is open to a page of any origin (Twitchat runs in the browser): its personal token, sent
 * in {@code Authorization}, is its only credential, so the answer allows no credentials (no cookie is ever sent
 * along) and any origin is safe. catapult-web answers the preflight itself, unauthenticated, before the relay; the
 * relay then keeps these headers over catapult-api's own. No other path gets CORS here.
 */
@Configuration
@Profile("!mock")
class CustomBindingsCorsConfig implements WebMvcConfigurer {

    /** Also matches {@code /api/v2/custom-bindings} itself. */
    static final String PATH = "/api/v2/custom-bindings/**";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(PATH)
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(false);
    }
}
