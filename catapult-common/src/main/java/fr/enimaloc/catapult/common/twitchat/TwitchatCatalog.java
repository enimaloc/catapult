package fr.enimaloc.catapult.common.twitchat;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every Twitchat action Catapult can send, generated from Twitchat's sources by
 * catapult-web/scripts/generate-twitchat-protocol.mjs (never edited by hand): its name per
 * protocol, and the form fields editing its data, each {@code path} a dotted path into it.
 */
public record TwitchatCatalog(int version, List<Action> actions) {

    public static final String RESOURCE = "/twitchat/catalog.json";

    /**
     * {@code kind}: "action" (it does something) or "data" (it only answers with data: never a
     * payload). {@code form}: "generated" (from Twitchat's typing), "override" (hand-written) or
     * "raw" (JSON only).
     */
    public record Action(String key, Map<String, String> names, String description, Integer priority,
                         String kind, String form, List<Field> fields) {
        public static final String DATA = "data";

        public Action {
            names = names == null ? Map.of() : Map.copyOf(names);
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        public boolean rawOnly() {
            return "raw".equals(form);
        }

        public boolean dataOnly() {
            return DATA.equals(kind);
        }
    }

    /**
     * {@code type}: text, textarea, number, switch, select, json, repeater (rows of {@code fields}),
     * list (a list of texts), choice (a select whose option values are JSON literals: true, false,
     * "toggle"…) or catapult-action (a helper with no {@code path}, never stored). A select's options are
     * {@code options}, or fetched live from Twitchat with the "get" action {@code source} names.
     * {@code showIf} (or null) shows the field only while a sibling holds one of some values.
     */
    public record Field(String path, String type, String label, String labelKey, boolean required,
                        List<Option> options, Map<String, String> source, Object defaultValue, List<Field> fields,
                        Condition showIf) {
    }

    /** The sibling field at {@code path} (same row, or same form) holds one of {@code values}. */
    public record Condition(String path, List<String> values) {
        public Condition {
            values = List.copyOf(values);
        }
    }

    public record Option(String value, String label, String labelKey) {
    }

    public TwitchatCatalog {
        actions = List.copyOf(actions);
    }

    public Optional<Action> action(String key) {
        return actions.stream().filter(action -> action.key().equals(key)).findFirst();
    }

    public static TwitchatCatalog load() {
        try (InputStream in = TwitchatCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing: run `npm run generate:twitchat-protocol` in catapult-web");
            }
            return JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
                    .readValue(in, TwitchatCatalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
