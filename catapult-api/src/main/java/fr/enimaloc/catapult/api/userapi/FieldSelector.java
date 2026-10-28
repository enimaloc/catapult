package fr.enimaloc.catapult.api.userapi;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
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

    /** The Swagger description of the {@code f} parameter. */
    static final String FIELD_DESCRIPTION = "One field of the answer, as plain text instead of "
            + "JSON: a dotted path (name, igdb.url, releaseDate.releaseDate). For chat bots that can't "
            + "read JSON; a list is joined with \", \", a missing field is empty.";

    private FieldSelector() {}

    /**
     * {@code answer} as is, or its field {@code field} alone as plain text (seen through {@code view}, when there
     * is one): for the chat bots that can't read JSON. An answer without a body (a 404) stays as it is.
     */
    static ResponseEntity<?> answer(JsonMapper mapper, ResponseEntity<?> answer, Class<?> view, String field) {
        if (field == null || field.isBlank() || answer.getBody() == null) return answer;
        return ResponseEntity.status(answer.getStatusCode())
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(select(mapper, answer.getBody(), view, field.strip()));
    }

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
