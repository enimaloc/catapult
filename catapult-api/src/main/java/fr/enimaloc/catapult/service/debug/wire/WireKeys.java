package fr.enimaloc.catapult.service.debug.wire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * Member keys ({@code id:}, {@code inv:}, {@code tag:}) that a decoded frame may name, and the matching of those
 * keys against a member's debug keys.
 */
public final class WireKeys {

    private WireKeys() {
    }

    /**
     * Every key the frame could name: numeric leaves (numbers or digit-only strings) as {@code id:} and
     * {@code inv:}, string leaves as {@code tag:}, and for each object every ordered pair of its direct string
     * leaves recombined as {@code tag:name#tag}.
     */
    public static Set<String> of(JsonNode node) {
        Set<String> keys = new HashSet<>();
        if (node != null) {
            collect(node, keys);
        }
        return keys;
    }

    /** Exact membership in {@code frameKeys}. */
    public static Predicate<String> concerns(Set<String> frameKeys) {
        return frameKeys::contains;
    }

    private static void collect(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            List<String> strings = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode child = entry.getValue();
                if (child.isString()) {
                    strings.add(child.asText());
                }
                collect(child, keys);
            }
            for (int i = 0; i < strings.size(); i++) {
                for (int j = 0; j < strings.size(); j++) {
                    if (i != j) {
                        keys.add("tag:" + lower(strings.get(i) + "#" + strings.get(j)));
                    }
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, keys);
            }
        } else if (node.isIntegralNumber()) {
            numeric(node.asText(), keys);
        } else if (node.isString()) {
            String text = node.asText();
            keys.add("tag:" + lower(text));
            if (isDigits(text)) {
                numeric(text, keys);
            }
        }
    }

    private static void numeric(String value, Set<String> keys) {
        keys.add("id:" + value);
        keys.add("inv:" + value);
    }

    private static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
