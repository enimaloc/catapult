package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Helix calls both chat transports make on a streamer's behalf (moderation, stream info,
 * profiles, follow dates, shoutouts), always with the streamer's own access token. Every call
 * logs and swallows its failures: chat commands degrade to "nothing happened" rather than error.
 */
@Slf4j
@Component
public class TwitchHelixChannelClient {

    private static final String HELIX_BANS_URL = "https://api.twitch.tv/helix/moderation/bans";
    private static final String HELIX_USERS_URL = "https://api.twitch.tv/helix/users";
    private static final String HELIX_STREAMS_URL = "https://api.twitch.tv/helix/streams";
    private static final String HELIX_FOLLOWERS_URL = "https://api.twitch.tv/helix/channels/followers";
    private static final String HELIX_SHOUTOUTS_URL = "https://api.twitch.tv/helix/chat/shoutouts";

    private static final String CLIENT_ID = "Client-Id";
    private static final String AUTHORIZATION = "Authorization";
    private static final String AUTHORIZATION_BEARER = "Bearer ";

    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    public TwitchHelixChannelClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** Bans {@code targetLogin} from the channel, or times them out when {@code durationSeconds > 0}. */
    public void moderate(UserAccount channel, String accessToken, String targetLogin, int durationSeconds, String reason) {
        String targetId = resolveUserId(accessToken, targetLogin);
        if (targetId == null) return;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user_id", targetId);
        if (durationSeconds > 0) data.put("duration", durationSeconds);
        if (reason != null && !reason.isBlank()) data.put("reason", reason);

        try {
            restClient.post()
                .uri(HELIX_BANS_URL + "?broadcaster_id=" + channel.getTwitchId() + "&moderator_id=" + channel.getTwitchId())
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                .header(CLIENT_ID, twitchClientId)
                .body(Map.of("data", data))
                .retrieve()
                .toBodilessEntity();
            log.info("[Helix] Moderated {} ({}s) for user {}", targetLogin, durationSeconds, channel.getId());
        } catch (Exception e) {
            log.warn("[Helix] Moderation failed for {} on user {}: {}", targetLogin, channel.getId(), e.getMessage());
        }
    }

    public void unban(UserAccount channel, String accessToken, String targetLogin) {
        String targetId = resolveUserId(accessToken, targetLogin);
        if (targetId == null) return;
        try {
            restClient.delete()
                .uri(HELIX_BANS_URL + "?broadcaster_id=" + channel.getTwitchId()
                    + "&moderator_id=" + channel.getTwitchId() + "&user_id=" + targetId)
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                .header(CLIENT_ID, twitchClientId)
                .retrieve()
                .toBodilessEntity();
            log.info("[Helix] Unbanned {} for user {}", targetLogin, channel.getId());
        } catch (Exception e) {
            log.warn("[Helix] Unban failed for {} on user {}: {}", targetLogin, channel.getId(), e.getMessage());
        }
    }

    public void shoutout(UserAccount channel, String accessToken, String targetLogin) {
        String targetId = resolveUserId(accessToken, targetLogin);
        if (targetId == null) return;
        try {
            restClient.post()
                .uri(HELIX_SHOUTOUTS_URL + "?from_broadcaster_id=" + channel.getTwitchId()
                    + "&to_broadcaster_id=" + targetId + "&moderator_id=" + channel.getTwitchId())
                .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                .header(CLIENT_ID, twitchClientId)
                .retrieve()
                .toBodilessEntity();
            log.info("[Helix] Shouted out {} for user {}", targetLogin, channel.getId());
        } catch (Exception e) {
            log.warn("[Helix] Shoutout failed for {} on user {}: {}", targetLogin, channel.getId(), e.getMessage());
        }
    }

    /** The channel's live stream, empty while offline. */
    public Optional<TwitchStreamInfo> streamInfo(UserAccount channel, String accessToken) {
        try {
            Map<String, Object> stream = firstData(HELIX_STREAMS_URL + "?user_id=" + channel.getTwitchId(), accessToken);
            if (stream == null) return Optional.empty();
            return Optional.of(new TwitchStreamInfo(
                (String) stream.get("title"), (String) stream.get("game_name"),
                ((Number) stream.get("viewer_count")).intValue(), Instant.parse((String) stream.get("started_at"))));
        } catch (Exception e) {
            log.warn("[Helix] Failed to fetch stream info for user {}: {}", channel.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<TwitchUserProfile> userProfile(String accessToken, String login) {
        Map<String, Object> profile = resolveUserJson(accessToken, login);
        if (profile == null) return Optional.empty();
        String createdAtRaw = (String) profile.get("created_at");
        Instant createdAt = createdAtRaw == null ? null : Instant.parse(createdAtRaw);
        return Optional.of(new TwitchUserProfile((String) profile.get("display_name"), createdAt));
    }

    /** When {@code targetLogin} followed the channel; empty when they don't (or can't be found). */
    public Optional<Instant> followedAt(UserAccount channel, String accessToken, String targetLogin) {
        String targetId = resolveUserId(accessToken, targetLogin);
        if (targetId == null) return Optional.empty();
        return fetchFollowedAt(channel, accessToken, targetId, targetLogin);
    }

    public Optional<Instant> followedAtById(UserAccount channel, String accessToken, String targetTwitchId) {
        return fetchFollowedAt(channel, accessToken, targetTwitchId, targetTwitchId);
    }

    private Optional<Instant> fetchFollowedAt(UserAccount channel, String accessToken, String targetId, String logLabel) {
        try {
            Map<String, Object> follow = firstData(HELIX_FOLLOWERS_URL + "?broadcaster_id=" + channel.getTwitchId()
                + "&moderator_id=" + channel.getTwitchId() + "&user_id=" + targetId, accessToken);
            if (follow == null) return Optional.empty();
            return Optional.of(Instant.parse((String) follow.get("followed_at")));
        } catch (Exception e) {
            log.warn("[Helix] Failed to fetch follow date for '{}' on user {}: {}", logLabel, channel.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String resolveUserId(String accessToken, String login) {
        Map<String, Object> user = resolveUserJson(accessToken, login);
        return user == null ? null : (String) user.get("id");
    }

    private Map<String, Object> resolveUserJson(String accessToken, String login) {
        try {
            return firstData(HELIX_USERS_URL + "?login=" + URLEncoder.encode(login, StandardCharsets.UTF_8), accessToken);
        } catch (Exception e) {
            log.warn("[Helix] Failed to resolve user for '{}': {}", login, e.getMessage());
            return null;
        }
    }

    /** The first entry of a Helix response's {@code data} array, or null when there is none. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstData(String url, String accessToken) {
        Map<String, Object> response = restClient.get()
            .uri(url)
            .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
            .header(CLIENT_ID, twitchClientId)
            .retrieve()
            .body(Map.class);
        if (response == null) return null;
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        return data == null || data.isEmpty() ? null : data.getFirst();
    }
}
