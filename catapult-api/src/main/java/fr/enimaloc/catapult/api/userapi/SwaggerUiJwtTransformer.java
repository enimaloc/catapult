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
@Component
public class SwaggerUiJwtTransformer extends SwaggerIndexPageTransformer {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final String JWT_WIDGET = """
            <div style="padding:10px 16px;background:#1b1b1b;color:#eee;font:14px/1.4 -apple-system,sans-serif;display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
              <label for="catapult-jwt">Dashboard JWT (optional — personalizes the "uuid" example):</label>
              <input type="password" id="catapult-jwt" style="flex:0 0 340px" placeholder="Paste your JWT here">
              <button id="catapult-jwt-save" type="button">Save &amp; reload</button>
              <button id="catapult-jwt-clear" type="button">Clear</button>
            </div>
            <script>
              (function () {
                var input = document.getElementById('catapult-jwt');
                var saved = window.localStorage.getItem('catapult-jwt');
                if (saved) { input.value = saved; }
                document.getElementById('catapult-jwt-save').addEventListener('click', function () {
                  if (input.value) { window.localStorage.setItem('catapult-jwt', input.value); }
                  else { window.localStorage.removeItem('catapult-jwt'); }
                  window.location.reload();
                });
                document.getElementById('catapult-jwt-clear').addEventListener('click', function () {
                  window.localStorage.removeItem('catapult-jwt');
                  input.value = '';
                  window.location.reload();
                });
              })();
            </script>
            """;

    private static final String REQUEST_INTERCEPTOR = """
            requestInterceptor: function (req) {
                  var token = window.localStorage.getItem('catapult-jwt');
                  if (token) { req.headers['Authorization'] = 'Bearer ' + token; }
                  return req;
                },
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
            String patched = html.replace("<div id=\"swagger-ui\"></div>", JWT_WIDGET + "\n    <div id=\"swagger-ui\"></div>");
            return new TransformedResource(resource, patched.getBytes(StandardCharsets.UTF_8));
        }
        return super.transform(request, resource, transformerChain);
    }
}
