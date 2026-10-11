package fr.enimaloc.catapult.service.notification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.CATEGORY_CHANGED_BY_CATAPULT;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.NO_GAME_DETECTED;
import static fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType.STREAM_STARTED;
import static org.assertj.core.api.Assertions.assertThat;

class TwitchatEventDefaultsTest {

    @Test
    void seedsEveryHistoricalEvent_butNotNoGameDetected() {
        assertThat(TwitchatEventDefaults.SEEDED).hasSize(5).doesNotContain(NO_GAME_DETECTED);
    }

    @Test
    void defaultChatMessage_matchesTheFormerHardcodedNotification() {
        assertThat(TwitchatEventDefaults.chatMessage(CATEGORY_CHANGED_BY_CATAPULT)).isEqualTo(Map.of(
                "message", "Catapult a changé la catégorie en {{gameName}}.",
                "style", "message",
                "icon", "change",
                "user", Map.of("name", "Catapult"),
                "actions", List.of(
                        Map.of("label", "Revert", "actionType", "url", "theme", "secondary",
                                "url", "{{baseUrl}}/widget/twitchat/action/{{action:REVERT_CATEGORY}}"),
                        Map.of("label", "Désactiver le bot", "actionType", "url", "theme", "alert",
                                "url", "{{baseUrl}}/widget/twitchat/action/{{action:DISABLE_BOT}}"))));
    }

    @Test
    void legacyPreset_keepsItsValues_andMovesTheAuthorUnderUser() {
        Map<String, Object> converted = TwitchatEventDefaults.fromLegacyPreset(STREAM_STARTED, Map.of(
                "message", "On est en live !", "icon", "", "authorName", "Bot",
                "actions", List.of(Map.of("label", "Stop", "actionType", "message", "message", "/off {{action:DISABLE_BOT}}"))));

        assertThat(converted).isEqualTo(Map.of(
                "message", "On est en live !", "style", "message", "icon", "live",
                "user", Map.of("name", "Bot"),
                "actions", List.of(Map.of("label", "Stop", "actionType", "message", "message", "/off {{action:DISABLE_BOT}}"))));
    }

    @Test
    void legacyPresetWithoutActions_getsTheDefaultButtons() {
        Map<String, Object> converted = TwitchatEventDefaults.fromLegacyPreset(STREAM_STARTED, Map.of("message", "Live"));
        assertThat(converted.get("actions")).isEqualTo(TwitchatEventDefaults.chatMessage(STREAM_STARTED).get("actions"));
    }
}
