package fr.enimaloc.catapult.api.userapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

class ApiVersionHeaderDefaultCustomizerTest {

    private final ApiVersionHeaderDefaultCustomizer customizer = new ApiVersionHeaderDefaultCustomizer();

    @Test
    void versionHeaderWithoutDefault_getsTheConfiguredDefaultVersion() {
        Parameter header = header(ApiV2.VERSION_HEADER, new StringSchema());

        customizer.customise(api(header));

        assertThat(header.getSchema().getDefault()).isEqualTo("2.0.0");
    }

    @Test
    void versionQueryParamWithoutDefault_getsTheConfiguredDefaultVersion() {
        Parameter query = new Parameter().in("query").name(ApiV2.VERSION_PARAM).schema(new StringSchema());

        customizer.customise(api(query));

        assertThat(query.getSchema().getDefault()).isEqualTo("2.0.0");
    }

    @Test
    void versionHeaderWithAnotherDefault_isLeftAlone() {
        StringSchema schema = new StringSchema();
        schema.setDefault("1");
        Parameter header = header(ApiV2.VERSION_HEADER, schema);

        customizer.customise(api(header));

        assertThat(header.getSchema().getDefault()).isEqualTo("1");
    }

    @Test
    void otherParameters_areLeftAlone() {
        Parameter otherHeader = header("Accept-Language", new StringSchema());
        Parameter query = new Parameter().in("query").name(ApiV2.VERSION_HEADER).schema(new StringSchema());

        customizer.customise(api(otherHeader, query));

        assertThat(otherHeader.getSchema().getDefault()).isNull();
        assertThat(query.getSchema().getDefault()).isNull();
    }

    private static Parameter header(String name, StringSchema schema) {
        return new Parameter().in("header").name(name).schema(schema);
    }

    private static OpenAPI api(Parameter... parameters) {
        Operation operation = new Operation();
        for (Parameter parameter : parameters) {
            operation.addParametersItem(parameter);
        }
        return new OpenAPI().paths(new Paths().addPathItem("/api/user/game/{uuid}", new PathItem().get(operation)));
    }
}
