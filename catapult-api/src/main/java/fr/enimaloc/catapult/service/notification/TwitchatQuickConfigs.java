package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.service.notification.dto.TwitchatQuickConfig;
import fr.enimaloc.catapult.service.notification.dto.TwitchatQuickConfigParam;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;

/**
 * Fixed, server-authored catalog of parameterized preset templates a streamer can pick from
 * instead of writing a preset's JSON by hand. Not user-created, not persisted — see
 * ApiTwitchatWidgetController for how {{baseUrl}} gets resolved before this reaches an HTTP
 * response, and the "+ Nouveau preset" UI for how {{param:KEY}} gets resolved client-side.
 *
 * <p>{@code label}/{@code description}/param labels are UI chrome and are resolved per-locale
 * from {@code twitchat_quick_config.<key>.*} (see resolve()) — same pattern as
 * {@link fr.enimaloc.catapult.chat.ChatCommandPresetCatalog}. {@code templateJson}'s own message
 * copy stays hardcoded French, matching {@link TwitchatDefaultPayloads}'s canonical Twitchat copy
 * — every notification/action label below is copied verbatim from that registry so a quick
 * config's payload reads exactly like the default notification it augments.
 *
 * <p>One pair of entries ("-link" / "-chat") per (event type, action type) combination that
 * {@link TwitchatDefaultPayloads} actually uses, covering the full cross product of
 * {@link TwitchatNotificationEventType} and {@link fr.enimaloc.catapult.domain.TwitchatActionType}.
 * "-link" delivers the action as a clickable button (Twitchat's "url" actionType); "-chat"
 * delivers it as a chat command carrying the action token as an argument, which — per
 * Twitchat's own actionType support — requires a matching Twitchat trigger the streamer must
 * create themselves (see each "-chat" key's description).
 */
public final class TwitchatQuickConfigs {

    private record RawParam(String key, String placeholder) {}

    private record RawConfig(String key, String groupKey, String variant, String eventType,
                              List<RawParam> params, String templateJson) {}

    private static final String LINK_SUFFIX = "-link";
    private static final String CHAT_SUFFIX = "-chat";

    private static RawConfig linkConfig(String key, TwitchatNotificationEventType eventType, String message,
                                         String icon, String actionLabel, String actionTypeToken, String theme) {
        String json = "{\"message\":\"" + message + "\",\"style\":\"message\",\"icon\":\"" + icon + "\","
                + "\"authorName\":\"Catapult\",\"actions\":[{\"label\":\"" + actionLabel + "\","
                + "\"actionType\":\"url\","
                + "\"url\":\"{{baseUrl}}/widget/twitchat/action/{{action:" + actionTypeToken + "}}\","
                + "\"theme\":\"" + theme + "\"}]}";
        String groupKey = key.substring(0, key.length() - LINK_SUFFIX.length());
        return new RawConfig(key, groupKey, "link", eventType.name(), List.of(), json);
    }

    private static RawConfig chatConfig(String key, TwitchatNotificationEventType eventType, String message,
                                         String icon, String actionLabel, String actionTypeToken, String theme) {
        String json = "{\"message\":\"" + message + "\",\"style\":\"message\",\"icon\":\"" + icon + "\","
                + "\"authorName\":\"Catapult\",\"actions\":[{\"label\":\"" + actionLabel + "\","
                + "\"actionType\":\"message\",\"message\":\"/{{param:command}} {{action:" + actionTypeToken + "}}\","
                + "\"theme\":\"" + theme + "\"}]}";
        String groupKey = key.substring(0, key.length() - CHAT_SUFFIX.length());
        return new RawConfig(key, groupKey, "chat", eventType.name(), List.of(new RawParam("command", "so")), json);
    }

    private static final String STREAM_STARTED_MESSAGE = "Le bot Catapult est actif.";
    private static final String CATEGORY_BY_CATAPULT_MESSAGE = "Catapult a changé la catégorie en {{gameName}}.";
    private static final String CATEGORY_MANUALLY_MESSAGE = "Catégorie changée manuellement en {{gameName}}.";
    private static final String BOT_ENABLED_MESSAGE = "Le bot a été activé.";
    private static final String BOT_DISABLED_MESSAGE = "Le bot a été désactivé.";

    private static final List<RawConfig> RAW = List.of(
            linkConfig("stream-started-disable-link", TwitchatNotificationEventType.STREAM_STARTED,
                    STREAM_STARTED_MESSAGE, "live", "Désactiver le bot", "DISABLE_BOT", "alert"),
            chatConfig("stream-started-disable-chat", TwitchatNotificationEventType.STREAM_STARTED,
                    STREAM_STARTED_MESSAGE, "live", "Désactiver le bot", "DISABLE_BOT", "secondary"),

            linkConfig("category-changed-by-catapult-revert-link", TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT,
                    CATEGORY_BY_CATAPULT_MESSAGE, "change", "Revert", "REVERT_CATEGORY", "secondary"),
            chatConfig("category-changed-by-catapult-revert-chat", TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT,
                    CATEGORY_BY_CATAPULT_MESSAGE, "change", "Revert", "REVERT_CATEGORY", "secondary"),

            linkConfig("category-changed-by-catapult-disable-bot-link", TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT,
                    CATEGORY_BY_CATAPULT_MESSAGE, "change", "Désactiver le bot", "DISABLE_BOT", "alert"),
            chatConfig("category-changed-by-catapult-disable-bot-chat", TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT,
                    CATEGORY_BY_CATAPULT_MESSAGE, "change", "Désactiver le bot", "DISABLE_BOT", "secondary"),

            linkConfig("category-changed-manually-bind-link", TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY,
                    CATEGORY_MANUALLY_MESSAGE, "user", "Définir par défaut pour ce jeu", "BIND_GAME_CATEGORY", "primary"),
            chatConfig("category-changed-manually-bind-chat", TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY,
                    CATEGORY_MANUALLY_MESSAGE, "user", "Définir par défaut pour ce jeu", "BIND_GAME_CATEGORY", "secondary"),

            linkConfig("category-changed-manually-revert-app-link", TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY,
                    CATEGORY_MANUALLY_MESSAGE, "user", "Retour à la catégorie de l'app", "REVERT_TO_APP_CATEGORY", "secondary"),
            chatConfig("category-changed-manually-revert-app-chat", TwitchatNotificationEventType.CATEGORY_CHANGED_MANUALLY,
                    CATEGORY_MANUALLY_MESSAGE, "user", "Retour à la catégorie de l'app", "REVERT_TO_APP_CATEGORY", "secondary"),

            linkConfig("bot-enabled-disable-link", TwitchatNotificationEventType.BOT_ENABLED,
                    BOT_ENABLED_MESSAGE, "online", "Désactiver le bot", "DISABLE_BOT", "secondary"),
            chatConfig("bot-enabled-disable-chat", TwitchatNotificationEventType.BOT_ENABLED,
                    BOT_ENABLED_MESSAGE, "online", "Désactiver le bot", "DISABLE_BOT", "secondary"),

            linkConfig("bot-disabled-enable-link", TwitchatNotificationEventType.BOT_DISABLED,
                    BOT_DISABLED_MESSAGE, "offline", "Réactiver le bot", "ENABLE_BOT", "secondary"),
            chatConfig("bot-disabled-enable-chat", TwitchatNotificationEventType.BOT_DISABLED,
                    BOT_DISABLED_MESSAGE, "offline", "Réactiver le bot", "ENABLE_BOT", "secondary")
    );

    public static List<TwitchatQuickConfig> resolve(MessageSource messageSource, Locale locale) {
        return RAW.stream()
                .map(rc -> new TwitchatQuickConfig(
                        rc.key(),
                        messageSource.getMessage("twitchat_quick_config." + rc.key() + ".label", null, locale),
                        messageSource.getMessage("twitchat_quick_config." + rc.key() + ".description", null, locale),
                        rc.eventType(),
                        rc.groupKey(),
                        messageSource.getMessage("twitchat_quick_config." + rc.groupKey() + ".group_label", null, locale),
                        rc.variant(),
                        rc.params().stream()
                                .map(p -> new TwitchatQuickConfigParam(
                                        p.key(),
                                        messageSource.getMessage(
                                                "twitchat_quick_config." + rc.key() + ".param." + p.key() + ".label",
                                                null, locale),
                                        p.placeholder()))
                                .toList(),
                        rc.templateJson()))
                .toList();
    }

    private TwitchatQuickConfigs() {
    }
}
