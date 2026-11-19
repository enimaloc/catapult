package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchStreamInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Freezes what the admin needs to judge a report later: everything here lives in memory (or on Twitch)
 * and can be gone or overwritten by then. The detected games carry no detection time: it isn't tracked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyContextCollector {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ActivityLogService activityLog;
    private final GameStateService gameState;
    private final TwitchChatService twitch;

    public ReportContext collect(UserAccount channel) {
        Optional<TwitchStreamInfo> stream = liveStream(channel);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("activityLog", activityLog.snapshot(channel.getId()));
        context.put("lastGame", game(gameState.getLastKnownGame(channel)));
        context.put("lastEndedGame", game(gameState.getLastEndedGame(channel)));
        context.put("twitchCategory", stream.map(TwitchStreamInfo::category).orElse(null));
        context.put("live", stream.isPresent());

        return new ReportContext(MAPPER.writeValueAsString(context),
                stream.map(TwitchStreamInfo::streamId).orElse(null),
                stream.map(TwitchStreamInfo::startedAt).orElse(null));
    }

    private Optional<TwitchStreamInfo> liveStream(UserAccount channel) {
        try {
            return twitch.getStreamInfo(channel);
        } catch (Exception e) {
            log.warn("[Emergency] Could not read the stream of user {}: {}", channel.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> game(Optional<DetectedGame> game) {
        return game.<Map<String, Object>>map(g -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("provider", g.getSourceType());
            out.put("sourceId", g.getSourceId());
            out.put("name", g.getSourceName());
            return out;
        }).orElse(null);
    }
}
