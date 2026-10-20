package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.BOT_DISABLED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.BOT_ENABLED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.STREAM_STARTED;

/**
 * The CUSTOM_CHAT_MESSAGE an account starts with on each event: its former hardcoded
 * notification (TwitchatDefaultPayloads), or its active legacy preset converted.
 */
public final class TwitchatEventDefaults {

    /** Every event that used to notify; NO_GAME_DETECTED is opt-in. */
    public static final List<TwitchatNotificationEventType> SEEDED =
            List.of(CATEGORY_CHANGED_BY_CATAPULT, CATEGORY_CHANGED_MANUALLY, STREAM_STARTED, BOT_ENABLED, BOT_DISABLED);

    public static final String ACTION_URL = "{{baseUrl}}/widget/twitchat/action/{{action:%s}}";

    private TwitchatEventDefaults() {
    }

    public static Map<String, Object> chatMessage(TwitchatNotificationEventType type) {
        TwitchatDefaultPayload defaults = TwitchatDefaultPayloads.DEFAULTS.get(type);
        List<Map<String, Object>> buttons = new ArrayList<>();
        defaults.actions().forEach((action, button) ->
                buttons.add(button(button.label(), "url", ACTION_URL.formatted(action.name()), null, button.theme())));
        return chatMessage(defaults.message(), defaults.style(), defaults.icon(), defaults.authorName(), buttons);
    }

    /** A legacy preset ({message, style, icon, authorName, actions}); its blank values take the default's. */
    public static Map<String, Object> fromLegacyPreset(TwitchatNotificationEventType type, Map<String, Object> preset) {
        TwitchatDefaultPayload defaults = TwitchatDefaultPayloads.DEFAULTS.get(type);
        Object rawButtons = preset.get("actions");
        Object buttons = rawButtons instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(raw -> legacyButton((Map<?, ?>) raw)).toList()
                : chatMessage(type).get("actions");
        return chatMessage(
                nonBlankOr(preset.get("message"), defaults.message()),
                nonBlankOr(preset.get("style"), defaults.style()),
                nonBlankOr(preset.get("icon"), defaults.icon()),
                nonBlankOr(preset.get("authorName"), defaults.authorName()),
                buttons);
    }

    private static Map<String, Object> legacyButton(Map<?, ?> raw) {
        return button(text(raw.get("label")), raw.get("actionType") == null ? "url" : text(raw.get("actionType")),
                text(raw.get("url")), text(raw.get("message")), text(raw.get("theme")));
    }

    private static Map<String, Object> chatMessage(String message, String style, String icon, String author, Object buttons) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", message);
        data.put("style", style);
        data.put("icon", icon);
        data.put("user", Map.of("name", author));
        data.put("actions", buttons);
        return data;
    }

    private static Map<String, Object> button(String label, String actionType, String url, String message, String theme) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("label", label);
        button.put("actionType", actionType);
        if (url != null) button.put("url", url);
        if (message != null) button.put("message", message);
        if (theme != null) button.put("theme", theme);
        return button;
    }

    private static String nonBlankOr(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
