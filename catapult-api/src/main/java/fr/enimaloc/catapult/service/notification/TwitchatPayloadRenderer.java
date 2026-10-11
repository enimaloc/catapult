package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPayload;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns configured payloads into what Twitchat receives, for one occurrence of an event:
 * {{variable}} and {{action:TYPE}} replaced in every string of the data. An action placeholder
 * resolves to the raw signed token of that Catapult action, minted once per type and only when
 * referenced. A payload referencing an action that doesn't apply right now (or an unknown or
 * malformed one) is skipped whole — never sent with the placeholder — except the buttons of a
 * CUSTOM_CHAT_MESSAGE, dropped one by one so the message itself still goes.
 */
public final class TwitchatPayloadRenderer {

    static final String CHAT_MESSAGE = "CUSTOM_CHAT_MESSAGE";
    // Permissive on purpose: any {{action:...}}-shaped text must be recognized as an attempted
    // placeholder (keyword in any case, spaces, unknown or wrong-case type) so the payload is
    // skipped, not shipped as is. The type itself still has to match exactly to resolve.
    private static final Pattern ACTION = Pattern.compile("\\{\\{\\s*(?i:action)\\s*:\\s*([^}]*?)\\s*\\}\\}");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*\\}\\}");

    private final Map<String, String> variables;
    private final Set<TwitchatActionType> applicable;
    private final Function<TwitchatActionType, String> tokenMinter;
    private final Map<TwitchatActionType, String> tokens = new EnumMap<>(TwitchatActionType.class);

    public TwitchatPayloadRenderer(Map<String, String> variables, Set<TwitchatActionType> applicable,
                                   Function<TwitchatActionType, String> tokenMinter) {
        this.variables = variables;
        this.applicable = applicable;
        this.tokenMinter = tokenMinter;
    }

    public Optional<TwitchatPayload> render(String action, Map<String, Object> data) {
        Map<String, Object> kept = new LinkedHashMap<>(data);
        if (CHAT_MESSAGE.equals(action) && kept.get("actions") instanceof List<?> buttons) {
            kept.put("actions", buttons.stream().filter(this::onlyApplicableActions).toList());
        }
        if (!onlyApplicableActions(kept)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked") Map<String, Object> rendered = (Map<String, Object>) substitute(kept);
        return Optional.of(new TwitchatPayload(action, rendered));
    }

    private boolean onlyApplicableActions(Object node) {
        return switch (node) {
            case Map<?, ?> map -> map.values().stream().allMatch(this::onlyApplicableActions);
            case List<?> list -> list.stream().allMatch(this::onlyApplicableActions);
            case String text -> {
                Matcher matcher = ACTION.matcher(text);
                while (matcher.find()) {
                    TwitchatActionType type = parse(matcher.group(1));
                    if (type == null || !applicable.contains(type)) {
                        yield false;
                    }
                }
                yield true;
            }
            case null, default -> true;
        };
    }

    private Object substitute(Object node) {
        return switch (node) {
            case Map<?, ?> map -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(String.valueOf(key), substitute(value)));
                yield copy;
            }
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                list.forEach(value -> copy.add(substitute(value)));
                yield copy;
            }
            case String text -> substituteVariables(substituteActions(text));
            case null, default -> node;
        };
    }

    private String substituteActions(String text) {
        return ACTION.matcher(text).replaceAll(match ->
                Matcher.quoteReplacement(tokens.computeIfAbsent(parse(match.group(1)), tokenMinter)));
    }

    private String substituteVariables(String text) {
        return VARIABLE.matcher(text).replaceAll(match -> {
            String value = variables.get(match.group(1));
            return Matcher.quoteReplacement(value == null ? match.group() : value);
        });
    }

    private static TwitchatActionType parse(String name) {
        try {
            return TwitchatActionType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
