package fr.enimaloc.catapult.api.userapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

class ApiVersionParameterCustomizerTest {

    private final ApiVersionParameterCustomizer customizer = new ApiVersionParameterCustomizer();

    @Test
    void baselineRoute_listsEverySupportedVersion() {
        Parameter header = header(ApiV2.VERSION_HEADER, schemaWithEnum("2"));

        customizer.customise(api(header));

        assertThat(header.getSchema().getEnum()).containsExactly("2", "3");
    }

    @Test
    void routeOfVersion3AndLater_listsVersion3Only() {
        Parameter header = header(ApiV2.VERSION_HEADER, schemaWithEnum("3"));

        customizer.customise(api(header));

        assertThat(header.getSchema().getEnum()).containsExactly("3");
    }

    @Test
    void versionParameters_haveNoHardCodedDefault_andSayTheServerAssignsIt() {
        Parameter header = header(ApiV2.VERSION_HEADER, schemaWithEnum("3", "2"));
        Parameter query = new Parameter().in("query").name(ApiV2.VERSION_PARAM).schema(schemaWithEnum("2"));

        customizer.customise(api(header, query));

        for (Parameter parameter : new Parameter[] {header, query}) {
            assertThat(parameter.getSchema().getDefault()).isNull();
            assertThat(parameter.getSchema().getEnum()).containsExactly("2", "3");
            assertThat(parameter.getDescription()).contains("assigns").contains("explicit value always wins");
        }
    }

    @Test
    void versionHeaderWithADeclaredDefault_keepsIt() {
        StringSchema schema = schemaWithEnum("2");
        schema.setDefault("2");
        Parameter header = header(ApiV2.VERSION_HEADER, schema);

        customizer.customise(api(header));

        assertThat(header.getSchema().getDefault()).isEqualTo("2");
    }

    @Test
    void otherParameters_areLeftAlone() {
        Parameter otherHeader = header("Accept-Language", new StringSchema());
        Parameter query = new Parameter().in("query").name(ApiV2.VERSION_HEADER).schema(new StringSchema());

        customizer.customise(api(otherHeader, query));

        assertThat(otherHeader.getSchema().getEnum()).isNull();
        assertThat(otherHeader.getDescription()).isNull();
        assertThat(query.getSchema().getEnum()).isNull();
        assertThat(query.getDescription()).isNull();
    }

    private static StringSchema schemaWithEnum(String... values) {
        StringSchema schema = new StringSchema();
        for (String value : values) {
            schema.addEnumItem(value);
        }
        return schema;
    }

    private static Parameter header(String name, StringSchema schema) {
        return new Parameter().in("header").name(name).schema(schema);
    }

    private static OpenAPI api(Parameter... parameters) {
        Operation operation = new Operation();
        for (Parameter parameter : parameters) {
            operation.addParametersItem(parameter);
        }
        return new OpenAPI().paths(new Paths().addPathItem("/api/me/game", new PathItem().get(operation)));
    }
}
