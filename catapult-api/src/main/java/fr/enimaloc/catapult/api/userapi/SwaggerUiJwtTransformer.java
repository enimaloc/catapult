package fr.enimaloc.catapult.api.userapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Replaces springdoc's default Swagger UI index page/initializer so a dashboard JWT can be
 * attached to every request Swagger UI makes — including the very first fetch of
 * /api/v3/api-docs itself, which the built-in "Authorize" dialog does NOT cover (it only applies
 * to later "Try it out" calls, well after the spec — and its example values — were already
 * generated server-side). See {@link ExampleUuidCustomizer}, which reads that request's auth to
 * resolve the "uuid" path parameter's example.
 *
 * <p>Registering this as a bean overrides springdoc's own {@code SwaggerIndexTransformer}
 * (it's declared {@code @ConditionalOnMissingBean}) — everything not touched here (css, other
 * assets, oauth2-redirect handling, ...) still goes through the inherited default behavior.
 */
//@Component TODO: Disables until completed
public class SwaggerUiJwtTransformer extends SwaggerIndexPageTransformer {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final String JWT_WIDGET = """
            <script>
              (function () {
                               const uuid = window.localStorage.getItem("catapult_widget_uuid");
                               if (!uuid) return;
            
                               const observer = new MutationObserver(() => {
                                 for (const el of document.querySelectorAll("input")) {
                                   if (el.value === "00000000-0000-0000-0001-000000000190") {
                                     el.value = uuid;
                                     el.dispatchEvent(new Event("input", { bubbles: true }));
                                     el.dispatchEvent(new Event("change", { bubbles: true }));
                                   }
                                 }
                               });
            
                               observer.observe(document.body, {
                                 childList: true,
                                 subtree: true
                               });
                             })();
            </script>
            """;

    private static final String REQUEST_INTERCEPTOR = """
            """;

    private static final String UUID_PLUGIN = """
        const CatapultPlugin = function() {
            return {
                wrapComponents: {
                    parameterRow: (Original) => (props) => {
                        const uuid = window.localStorage.getItem('catapult_widget_uuid');

                        if (uuid && props.parameter?.name === 'uuid') {
                            props = {
                                ...props,
                                parameter: {
                                    ...props.parameter,
                                    example: uuid
                                }
                            };
                        }

                        return Original(props);
                    }
                }
            };
        };
        """;

    public SwaggerUiJwtTransformer(SwaggerUiConfigProperties swaggerUiConfig, SwaggerUiOAuthProperties swaggerUiOAuthProperties,
            SwaggerWelcomeCommon swaggerWelcomeCommon, ObjectMapperProvider objectMapperProvider) {
        super(swaggerUiConfig, swaggerUiOAuthProperties, swaggerWelcomeCommon, objectMapperProvider);
    }

    @Override
    public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain transformerChain) throws IOException {
        String url = resource.getURL().toString();
        if (PATH_MATCHER.match("**/swagger-ui/**/swagger-initializer.js", url)) {
            Resource base = super.transform(request, resource, transformerChain);
            String js = new String(base.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String patched = js.replaceFirst("SwaggerUIBundle\\(\\{", "SwaggerUIBundle({\n    " + REQUEST_INTERCEPTOR);
            return new TransformedResource(resource, patched.getBytes(StandardCharsets.UTF_8));
        }
        if (PATH_MATCHER.match("**/swagger-ui/**/index.html", url)) {
            String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String patched = html.replace("<div id=\"swagger-ui\"></div>",  "<div id=\"swagger-ui\"></div>\n" + JWT_WIDGET);
            return new TransformedResource(resource, patched.getBytes(StandardCharsets.UTF_8));
        }
        return super.transform(request, resource, transformerChain);
    }
}
