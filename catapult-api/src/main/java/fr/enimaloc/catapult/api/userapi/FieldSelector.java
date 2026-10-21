package fr.enimaloc.catapult.api.userapi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * The {@code ?f=} of the user API: one field of an answer, as plain text, for the chat bots that
 * can't read JSON (StreamElements' {@code $(customapi)}, Wizebot's {@code $urlcall}). The field is
 * a dotted path into the JSON the endpoint would have answered ({@code name}, {@code
 * releaseDate.releaseDate}); a list is joined with ", ", an object stays JSON, a missing or null
 * field is empty.
 */
final class FieldSelector {

    private FieldSelector() {}

    static String select(JsonMapper mapper, Object answer, Class<?> view, String path) {
        String json = view == null ? mapper.writeValueAsString(answer)
                : mapper.writerWithView(view).writeValueAsString(answer);
        JsonNode node = mapper.readTree(json);
        for (String part : path.split("\\.", -1)) {
            node = node.path(part);
        }
        return text(node);
    }

    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return "";
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false).map(FieldSelector::text)
                    .filter(s -> !s.isEmpty()).collect(Collectors.joining(", "));
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }
}
