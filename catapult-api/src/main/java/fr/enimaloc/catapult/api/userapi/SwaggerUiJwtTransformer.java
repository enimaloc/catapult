package fr.enimaloc.catapult.api.userapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Swaps the generic backdoor "uuid" example for the caller's own widget token, entirely
 * client-side: the dashboard (twitchat-settings.html) already writes it to
 * {@code localStorage['catapult_widget_uuid']} for a logged-in user, and this page is same-origin
 * with it (both sit behind nginx on the same host) — no server round-trip, no auth token of any
 * kind ever touches this feature.
 *
 * <p>Registering this as a bean overrides springdoc's own {@code SwaggerIndexTransformer}
 * (it's declared {@code @ConditionalOnMissingBean}) — everything not touched here (css, other
 * assets, oauth2-redirect handling, ...) still goes through the inherited default behavior.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SwaggerUiJwtTransformer extends SwaggerIndexPageTransformer {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final String UUID_SWAP_SCRIPT = """
            <script>
              (function () {
                var uuid = window.localStorage.getItem('catapult_widget_uuid');
                if (!uuid) { return; }

                // Swagger UI's <input> is React-controlled: assigning el.value directly goes
                // through the setter React itself wrapped, so React's tracked state never notices
                // the change and "Execute" still submits the old value even though the field LOOKS
                // updated. Going through the native setter first (bypassing React's override) is
                // what makes the follow-up 'input' event actually register as a real change.
                var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;

                // Swagger UI only materializes an <input> for a parameter once "Try it out" is
                // clicked, pre-filled from the (generic, backend-baked) example — so watch for it
                // rather than trying to patch the spec/schema up front.
                new MutationObserver(function () {
                  document.querySelectorAll('input').forEach(function (el) {
                    if (el.value === '%s') {
                      nativeInputValueSetter.call(el, uuid);
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                  });
                }).observe(document.body, { childList: true, subtree: true });
              })();
            </script>
            """.formatted(ApiV2.GENERIC_UUID_EXAMPLE);

    public SwaggerUiJwtTransformer(SwaggerUiConfigProperties swaggerUiConfig, SwaggerUiOAuthProperties swaggerUiOAuthProperties,
            SwaggerWelcomeCommon swaggerWelcomeCommon, ObjectMapperProvider objectMapperProvider) {
        super(swaggerUiConfig, swaggerUiOAuthProperties, swaggerWelcomeCommon, objectMapperProvider);
    }

    @Override
    public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain transformerChain) throws IOException {
        String url = resource.getURL().toString();
        if (PATH_MATCHER.match("**/swagger-ui/**/index.html", url)) {
            String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String patched = html.replace("<div id=\"swagger-ui\"></div>", "<div id=\"swagger-ui\"></div>\n" + UUID_SWAP_SCRIPT);
            return new TransformedResource(resource, patched.getBytes(StandardCharsets.UTF_8));
        }
        return super.transform(request, resource, transformerChain);
    }
}
