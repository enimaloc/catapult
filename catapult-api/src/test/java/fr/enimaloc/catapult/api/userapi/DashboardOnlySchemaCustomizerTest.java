package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardOnlySchemaCustomizerTest {

    @Test
    void theDocumentedStateKeepsNeitherTheExtensionsNorTheLimit() {
        Schema<?> state = new Schema<>();
        state.addProperty("enabled", new Schema<>());
        state.addProperty("extensions", new Schema<>());
        state.addProperty("limit", new Schema<>());
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("CustomBindingsStateDto", state));

        new DashboardOnlySchemaCustomizer().customise(openApi);

        assertThat(state.getProperties()).containsOnlyKeys("enabled");
    }
}
