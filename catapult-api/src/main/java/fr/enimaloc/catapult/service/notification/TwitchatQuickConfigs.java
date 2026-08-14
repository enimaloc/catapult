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
 * copy stays hardcoded French, matching {@link TwitchatDefaultPayloads}'s canonical Twitchat copy.
 */
public final class TwitchatQuickConfigs {

    private record RawParam(String key, String placeholder) {}

    private record RawConfig(String key, String eventType, List<RawParam> params, String templateJson) {}

    private static final List<RawConfig> RAW = List.of(
            new RawConfig(
                    "stream-started-disable-link",
                    TwitchatNotificationEventType.STREAM_STARTED.name(),
                    List.of(),
                    "{\"message\":\"Le bot Catapult est actif.\",\"style\":\"message\",\"icon\":\"live\","
                            + "\"authorName\":\"Catapult\",\"actions\":[{\"label\":\"Désactiver le bot\","
                            + "\"actionType\":\"url\","
                            + "\"url\":\"{{baseUrl}}/widget/twitchat/action/{{action:DISABLE_BOT}}\","
                            + "\"theme\":\"alert\"}]}"
            ),
            new RawConfig(
                    "stream-started-disable-chat",
                    TwitchatNotificationEventType.STREAM_STARTED.name(),
                    List.of(new RawParam("command", "so")),
                    "{\"message\":\"Le bot Catapult est actif.\",\"style\":\"message\",\"icon\":\"live\","
                            + "\"authorName\":\"Catapult\",\"actions\":[{\"label\":\"Désactiver le bot (chat)\","
                            + "\"actionType\":\"chat\",\"message\":\"/{{param:command}} {{action:DISABLE_BOT}}\","
                            + "\"theme\":\"secondary\"}]}"
            )
    );

    public static List<TwitchatQuickConfig> resolve(MessageSource messageSource, Locale locale) {
        return RAW.stream()
                .map(rc -> new TwitchatQuickConfig(
                        rc.key(),
                        messageSource.getMessage("twitchat_quick_config." + rc.key() + ".label", null, locale),
                        messageSource.getMessage("twitchat_quick_config." + rc.key() + ".description", null, locale),
                        rc.eventType(),
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
