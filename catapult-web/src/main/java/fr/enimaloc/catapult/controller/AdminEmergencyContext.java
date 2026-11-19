package fr.enimaloc.catapult.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * An emergency report's context as the admin tab shows it: the activity log, the last games and the Twitch category.
 * What a viewer influenced (log messages, names) is kept as plain strings, for the template to print as text.
 */
record AdminEmergencyContext(List<Line> log, Game lastGame, Game lastEndedGame, String twitchCategory, boolean live) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One activity log line; {@code at} is null when the timestamp is not an instant ({@code rawAt}: as sent). */
    record Line(Instant at, String rawAt, String level, String message) {}

    record Game(String provider, String sourceId, String name) {}

    /** The context in {@code json}; null when there is none or it cannot be read (shown as "context unavailable"). */
    static AdminEmergencyContext parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) return null;
            List<Line> log = new ArrayList<>();
            JsonNode lines = root.path("activityLog");
            if (lines.isArray()) {
                for (JsonNode line : lines) {
                    String raw = text(line.path("timestamp"));
                    log.add(new Line(instant(raw), raw, text(line.path("level")), text(line.path("message"))));
                }
            }
            return new AdminEmergencyContext(List.copyOf(log), game(root.path("lastGame")),
                    game(root.path("lastEndedGame")), text(root.path("twitchCategory")), root.path("live").asBoolean());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Game game(JsonNode node) {
        if (!node.isObject()) return null;
        return new Game(text(node.path("provider")), text(node.path("sourceId")), text(node.path("name")));
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static Instant instant(String raw) {
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
