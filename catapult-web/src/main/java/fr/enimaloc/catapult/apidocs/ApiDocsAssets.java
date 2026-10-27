package fr.enimaloc.catapult.apidocs;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Swagger UI's files (the org.webjars:swagger-ui jar, the version springdoc gives catapult-api), served by the site
 * under {@code /_static/swagger-ui/<version>/} as mdui's are under {@code /_static/mdui/}: a static path, which the
 * session and monitoring filters leave alone. No CDN.
 */
@Configuration
public class ApiDocsAssets implements WebMvcConfigurer {
    /** The org.webjars:swagger-ui version of build.gradle.kts, which pages/api-docs.html names in its paths. */
    public static final String SWAGGER_UI_VERSION = "5.34.0";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/_static/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/");
    }
}
