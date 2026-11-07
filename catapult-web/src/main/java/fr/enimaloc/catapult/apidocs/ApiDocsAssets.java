package fr.enimaloc.catapult.apidocs;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.Set;

/**
 * Swagger UI's files (the org.webjars:swagger-ui jar, the version springdoc gives catapult-api), served by the site
 * under {@code /_static/swagger-ui/<version>/} as mdui's are under {@code /_static/mdui/}: a static path, which the
 * session and monitoring filters leave alone. No CDN. Only what pages/api-docs.html loads is served (the bundle
 * and its stylesheet, which loads nothing else): not the jar's own page, initializer or OAuth2 redirect.
 */
@Configuration
public class ApiDocsAssets implements WebMvcConfigurer {
    /** The org.webjars:swagger-ui version of build.gradle.kts, which pages/api-docs.html names in its paths. */
    public static final String SWAGGER_UI_VERSION = "5.34.0";
    private static final String JAR = "classpath:/META-INF/resources/webjars/swagger-ui/";
    static final Set<String> SERVED = Set.of(
            SWAGGER_UI_VERSION + "/swagger-ui-bundle.js", SWAGGER_UI_VERSION + "/swagger-ui.css");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/_static/swagger-ui/**")
                .addResourceLocations(JAR)
                .resourceChain(true)
                .addResolver(new Only(SERVED));
        // Spring Boot would serve the whole jar again under /webjars/ (and its /** under META-INF/resources):
        // this more specific mapping answers nothing there.
        registry.addResourceHandler("/webjars/swagger-ui/**")
                .addResourceLocations(JAR)
                .resourceChain(false)
                .addResolver(new Only(Set.of()));
    }

    /** Resolves the given paths (relative to the jar's root) only. */
    private static final class Only extends PathResourceResolver {
        private final Set<String> paths;

        Only(Set<String> paths) {
            this.paths = paths;
        }

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            return paths.contains(resourcePath) ? super.getResource(resourcePath, location) : null;
        }
    }
}
