package fr.enimaloc.catapult.service.emergency;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** A chat command addressed to the system account: {@code @bot <command> [args...]}. */
public record SystemMention(String command, List<String> args) {

    /**
     * Reads the {@code message} of a {@code channel.chat.message} event. It is a command only when its first fragment
     * mentions the system account; the text of the fragments after it gives the command (lowercased) and its arguments.
     * The system id is only asked for when the message starts with a mention, so ordinary chat messages cost nothing.
     * {@code on} and {@code off} count only when nothing follows them: "on verra" is a sentence, not a command.
     */
    public static Optional<SystemMention> parse(JsonNode message, Supplier<String> systemTwitchId) {
        JsonNode fragments = message.path("fragments");
        JsonNode first = fragments.path(0);
        if (!"mention".equals(first.path("type").asText())) {
            return Optional.empty();
        }
        String systemId = systemTwitchId.get();
        if (systemId == null || systemId.isBlank()
                || !systemId.equals(first.path("mention").path("user_id").asText(null))) {
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
        String command = words[0].toLowerCase(Locale.ROOT);
        if (words.length > 1 && ("on".equals(command) || "off".equals(command))) {
            return Optional.empty();
        }
        return Optional.of(new SystemMention(command, List.copyOf(Arrays.asList(words).subList(1, words.length))));
    }
}
