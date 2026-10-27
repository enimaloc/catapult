package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.accept.SemanticApiVersionParser;

/**
 * Gives the {@link ApiV2#VERSION_HEADER} header parameter of every documented operation the
 * default version {@link ApiVersioningConfig} configures, as {@code /v3/api-docs} has always shown
 * it. springdoc 3.2 stopped writing that default; the frozen api-docs answer still carries it.
 * A header that already has a default keeps it.
 *
 * <p>The value is {@link ApiV2#VERSION} as Spring's default {@link SemanticApiVersionParser}, the one
 * {@link ApiVersioningConfig} keeps, reads it ("2" is "2.0.0"). Parsed here rather than read from the
 * MVC {@code ApiVersionStrategy}, which contexts without Spring MVC do not have.
 */
@Component
public class ApiVersionHeaderDefaultCustomizer implements OpenApiCustomizer {

    private final String defaultVersion = new SemanticApiVersionParser().parseVersion(ApiV2.VERSION).toString();

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        for (PathItem path : openApi.getPaths().values()) {
            for (Operation operation : path.readOperations()) {
                if (operation.getParameters() == null) {
                    continue;
                }
                for (Parameter parameter : operation.getParameters()) {
                    if (isVersionHeader(parameter) && parameter.getSchema().getDefault() == null) {
                        setDefault(parameter.getSchema());
                    }
                }
            }
        }
    }

    private static boolean isVersionHeader(Parameter parameter) {
        return "header".equals(parameter.getIn())
                && ApiV2.VERSION_HEADER.equalsIgnoreCase(parameter.getName())
                && parameter.getSchema() != null;
    }

    @SuppressWarnings("unchecked")
    private void setDefault(Schema<?> schema) {
        ((Schema<Object>) schema).setDefault(defaultVersion);
    }
}
