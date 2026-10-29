package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Keeps the dashboard-only extension layouts out of the documented token API. The custom bindings' list is shared with
 * the dashboard, whose {@code CustomBindingsStateDto#extensions} the token API never fills (see
 * {@code CustomBindingViews#publicState}), so the documented schema stays the one the token API has always had.
 */
@Component
public class DashboardOnlySchemaCustomizer implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Schema<?> state = openApi.getComponents().getSchemas().get("CustomBindingsStateDto");
        if (state != null && state.getProperties() != null) {
            state.getProperties().remove("extensions");
        }
        openApi.getComponents().getSchemas().remove("ExtensionSlotDto");
    }
}
