package fr.enimaloc.catapult.service.emergency;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A chat command addressed to the system account: {@code @bot <command> [args...]}. */
public record SystemMention(String command, List<String> args) {

    /**
     * Reads the {@code message} of a {@code channel.chat.message} event. It is a command only when its first fragment
     * mentions the system account; the text of the fragments after it gives the command (lowercased) and its arguments.
     */
    public static Optional<SystemMention> parse(JsonNode message, String systemTwitchId) {
        if (systemTwitchId == null || systemTwitchId.isBlank()) {
            return Optional.empty();
        }
        JsonNode fragments = message.path("fragments");
        JsonNode first = fragments.path(0);
        if (!"mention".equals(first.path("type").asText())
                || !systemTwitchId.equals(first.path("mention").path("user_id").asText(null))) {
            return Optional.empty();
        }
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < fragments.size(); i++) {
            rest.append(fragments.get(i).path("text").asText(""));
        }
        String trimmed = rest.toString().trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String[] words = trimmed.split("\\s+");
        return Optional.of(new SystemMention(words[0].toLowerCase(Locale.ROOT),
                List.copyOf(Arrays.asList(words).subList(1, words.length))));
    }
}
