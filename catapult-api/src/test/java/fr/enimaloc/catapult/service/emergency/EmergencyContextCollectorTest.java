package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchStreamInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmergencyContextCollectorTest {

    private final ActivityLogService activityLog = new ActivityLogService();
    private final GameStateService gameState = mock(GameStateService.class);
    private final TwitchChatService twitch = mock(TwitchChatService.class);
    private final EmergencyContextCollector collector = new EmergencyContextCollector(activityLog, gameState, twitch);
    private final UserAccount channel = new UserAccount();

    EmergencyContextCollectorTest() {
        channel.setId(UUID.randomUUID());
        when(gameState.getLastKnownGame(channel)).thenReturn(Optional.empty());
        when(gameState.getLastEndedGame(channel)).thenReturn(Optional.empty());
        when(twitch.getStreamInfo(channel)).thenReturn(Optional.empty());
    }

    private static JsonNode parse(String json) {
        return JsonMapper.builder().build().readTree(json);
    }

    @Test
    void collect_offline_hasNoStream() {
        ReportContext context = collector.collect(channel);

        assertThat(context.streamId()).isNull();
        assertThat(context.streamStartedAt()).isNull();
        JsonNode json = parse(context.json());
        assertThat(json.get("live").asBoolean()).isFalse();
        assertThat(json.get("twitchCategory").isNull()).isTrue();
        assertThat(json.get("lastGame").isNull()).isTrue();
    }

    @Test
    void collect_live_capturesStreamIdAndCategory() {
        Instant startedAt = Instant.parse("2026-11-19T12:00:00Z");
        when(twitch.getStreamInfo(channel))
                .thenReturn(Optional.of(new TwitchStreamInfo("s-1", "Title", "Celeste", 5, startedAt)));
        when(gameState.getLastKnownGame(channel))
                .thenReturn(Optional.of(new DetectedGame("123", GameBinding.SourceType.XBOX, "Halo")));
        when(gameState.getLastEndedGame(channel))
                .thenReturn(Optional.of(new DetectedGame("9", GameBinding.SourceType.XBOX, "Old")));

        ReportContext context = collector.collect(channel);

        assertThat(context.streamId()).isEqualTo("s-1");
        assertThat(context.streamStartedAt()).isEqualTo(startedAt);
        JsonNode json = parse(context.json());
        assertThat(json.get("live").asBoolean()).isTrue();
        assertThat(json.get("twitchCategory").asString()).isEqualTo("Celeste");
        assertThat(json.get("lastGame").get("sourceId").asString()).isEqualTo("123");
        assertThat(json.get("lastGame").get("name").asString()).isEqualTo("Halo");
        assertThat(json.get("lastEndedGame").get("name").asString()).isEqualTo("Old");
    }

    @Test
    void collect_copiesTheActivityLog() {
        activityLog.addEntry(channel.getId(), "INFO", "first");
        activityLog.addEntry(channel.getId(), "WARN", "second");

        ReportContext context = collector.collect(channel);
        activityLog.addEntry(channel.getId(), "INFO", "after the report");

        JsonNode log = parse(context.json()).get("activityLog");
        assertThat(log).hasSize(2);
        assertThat(log.get(1).get("level").asString()).isEqualTo("WARN");
        assertThat(log.get(1).get("message").asString()).isEqualTo("second");
        assertThat(log.get(1).get("timestamp").asString()).isNotBlank();
    }
}
