package fr.enimaloc.catapult.api.userapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.accept.SemanticApiVersionParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Documents the {@link ApiV2#VERSION_HEADER} header parameter, and the {@link ApiV2#VERSION_PARAM} query parameter
 * that stands in for it, of every documented operation: every supported version the operation answers, and no
 * default value — the default is the version the server assigns the caller (the {@code api.default-version}
 * experiment, see {@link ExperimentDefaultVersionResolver}), which no fixed value can tell. A default an operation
 * declares itself is kept.
 *
 * <p>springdoc lists the versions of the operation's mappings ({@code "2"} for a {@code "2+"} one): the lowest of
 * them is where the operation starts, and every supported version from it on is listed, in order.
 */
@Component
public class ApiVersionParameterCustomizer implements OpenApiCustomizer {

    static final String DESCRIPTION = "API version. Defaults to the version the server assigns you (it may move to "
            + "a newer one over time); an explicit value always wins.";

    private static final List<String> SUPPORTED = List.of(ApiV2.VERSION, ApiV2.VERSION_3);

    private final SemanticApiVersionParser parser = new SemanticApiVersionParser();

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
                    if (isVersionParameter(parameter)) {
                        document(parameter);
                    }
                }
            }
        }
    }

    private static boolean isVersionParameter(Parameter parameter) {
        boolean named = "header".equals(parameter.getIn()) && ApiV2.VERSION_HEADER.equalsIgnoreCase(parameter.getName())
                || "query".equals(parameter.getIn()) && ApiV2.VERSION_PARAM.equals(parameter.getName());
        return named && parameter.getSchema() != null;
    }

    @SuppressWarnings("unchecked")
    private void document(Parameter parameter) {
        Schema<Object> schema = (Schema<Object>) parameter.getSchema();
        schema.setEnum(new ArrayList<>(answeredFrom(schema.getEnum())));
        if (parameter.getDescription() == null) {
            parameter.setDescription(DESCRIPTION);
        }
    }

    /** The supported versions from the lowest of {@code mapped} on; all of them when it names none. */
    private List<Object> answeredFrom(List<Object> mapped) {
        if (mapped == null || mapped.isEmpty()) {
            return new ArrayList<>(SUPPORTED);
        }
        SemanticApiVersionParser.Version lowest = mapped.stream()
                .map(value -> parser.parseVersion(String.valueOf(value)))
                .min(SemanticApiVersionParser.Version::compareTo)
                .orElseThrow();
        List<Object> answered = new ArrayList<>();
        for (String version : SUPPORTED) {
            if (parser.parseVersion(version).compareTo(lowest) >= 0) {
                answered.add(version);
            }
        }
        return answered;
    }
}
