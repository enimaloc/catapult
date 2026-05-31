package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock.twitch", havingValue = "false", matchIfMissing = true)
public class TwitchServiceImpl implements TwitchService {

    private static final String TWITCH_API_URL = "https://api.twitch.tv/helix";

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestClient restClient;
    private final TwitchCategoryService twitchCategoryService;

    public static final String CLIENT_ID = "Client-Id";
    public static final String AUTHORIZATION = "Authorization";
    public static final String AUTHORIZATION_BEARER = "Bearer ";

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Override
    public void updateChannel(UserAccount user, GameBinding binding) {
        if (binding.getStatus() == GameBinding.Status.INCOMPLETE || binding.isIgnored()) {
            log.debug("Skipping Twitch update for user {} — binding is {} or ignored",
                user.getId(), binding.getStatus());
            return;
        }

        oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .ifPresentOrElse(
                token -> doUpdateChannel(user, binding, token),
                () -> log.warn("No Twitch token found for user {}", user.getId())
            );
    }

    private void doUpdateChannel(UserAccount user, GameBinding binding, OAuthToken token) {
        String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", binding.getTwitchGameId());

        boolean globalCclEnabled = userSettingsRepository.findById(user.getId())
            .map(UserSettings::isCclFeatureEnabled)
            .orElse(true);

        if (globalCclEnabled && binding.isCclEnabled()) {
            body.put("content_classification_labels", buildCclPayload(binding.getCcls()));
        }

        try {
            restClient.patch()
                .uri(TWITCH_API_URL + "/channels?broadcaster_id=" + user.getTwitchId())
                    .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                    .header(CLIENT_ID, twitchClientId)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .toBodilessEntity();

            log.info("Twitch channel updated for user {} — game_id={}, ccls={}",
                user.getId(), binding.getTwitchGameId(), binding.getCcls());

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("Twitch token invalid for user {} — pausing bot", user.getId());
                user.setBotEnabled(false);
                userAccountRepository.save(user);
            } else if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Twitch rate limit hit for user {} — will retry next cycle", user.getId());
            } else {
                log.error("Twitch API error for user {}: {} {}", user.getId(), e.getStatusCode(), e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error updating Twitch channel for user {}", user.getId(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> findCategoryIdByName(UserAccount user, String gameName) {
        String accessToken = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .map(t -> tokenEncryptionService.decrypt(t.getAccessToken()))
            .orElse("");

        if (!accessToken.isBlank() && !twitchClientId.isBlank()) {
            log.debug("findCategoryIdByName '{}' — trying exact match via user token", gameName);
            try {
                Map<String, Object> response = restClient.get()
                    .uri(TWITCH_API_URL + "/games?name={name}", gameName)
                        .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                        .header(CLIENT_ID, twitchClientId)
                    .retrieve()
                    .body(Map.class);

                if (response != null) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                    if (data != null && !data.isEmpty()) {
                        String id = (String) data.get(0).get("id");
                        log.debug("findCategoryIdByName '{}' — exact match found: {}", gameName, id);
                        return Optional.ofNullable(id);
                    }
                    log.debug("findCategoryIdByName '{}' — exact match returned empty data", gameName);
                }
            } catch (Exception e) {
                log.warn("findCategoryIdByName '{}' — user-token exact match failed: {}", gameName, e.getMessage());
            }
        } else {
            log.debug("findCategoryIdByName '{}' — no user token or client-id, skipping exact match", gameName);
        }

        log.debug("findCategoryIdByName '{}' — falling back to app-token search", gameName);
        String normalizedQuery = normalizeTitle(gameName);
        List<TwitchCategory> candidates = twitchCategoryService.searchCategories(gameName);
        log.debug("findCategoryIdByName '{}' — search returned {} candidates: {}",
                gameName, candidates.size(),
                candidates.stream().map(c -> c.name() + "(" + c.id() + ")").toList());
        return candidates.stream()
            .filter(c -> normalizeTitle(c.name()).equals(normalizedQuery))
            .findFirst()
            .map(c -> {
                log.debug("findCategoryIdByName '{}' — matched via normalize: {} → {}", gameName, c.name(), c.id());
                return c.id();
            });
    }

    private static String normalizeTitle(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @Override
    public List<TwitchCategory> searchCategories(UserAccount user, String query) {
        return twitchCategoryService.searchCategories(query);
    }

    // Twitch broadcaster-settable CCLs (MatureGame is set automatically by Twitch, not included)
    private static final List<String> EDITABLE_CCL_IDS = List.of(
        "ViolentGraphic", "SexualThemes", "DrugsIntoxication", "Gambling", "ProfanityVulgarity"
    );

    private List<Map<String, Object>> buildCclPayload(Set<String> cclIds) {
        return EDITABLE_CCL_IDS.stream()
            .map(id -> Map.<String, Object>of("id", id, "is_enabled", cclIds.contains(id)))
            .toList();
    }

    @Override
    public void resetToDefault(UserAccount user) {
        userSettingsRepository.findById(user.getId()).ifPresent(settings -> {
            if (settings.getNoGameTwitchGameId() == null || settings.getNoGameTwitchGameId().isBlank()) {
                log.debug("No default category configured for user {} — skipping reset", user.getId());
                return;
            }
            oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .ifPresentOrElse(
                    token -> doResetToDefault(user, settings, token),
                    () -> log.warn("No Twitch token for user {} during reset", user.getId())
                );
        });
    }

    private void doResetToDefault(UserAccount user, UserSettings settings, OAuthToken token) {
        String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", settings.getNoGameTwitchGameId());
        if (settings.isCclFeatureEnabled() && !settings.getNoGameCcls().isEmpty()) {
            body.put("content_classification_labels", buildCclPayload(settings.getNoGameCcls()));
        }
        try {
            restClient.patch()
                .uri(TWITCH_API_URL + "/channels?broadcaster_id=" + user.getTwitchId())
                    .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                    .header(CLIENT_ID, twitchClientId)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .toBodilessEntity();
            log.info("Twitch channel reset to default for user {} — game_id={}, ccls={}",
                user.getId(), settings.getNoGameTwitchGameId(), settings.getNoGameCcls());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("Twitch token invalid for user {} during reset — pausing bot", user.getId());
                user.setBotEnabled(false);
                userAccountRepository.save(user);
            } else if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Twitch rate limit hit for user {} during reset — skipping", user.getId());
            } else {
                log.error("Twitch API error during reset for user {}: {} {}",
                    user.getId(), e.getStatusCode(), e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error resetting Twitch channel for user {}", user.getId(), e);
        }
    }

    @Override
    public List<String> getModeratedChannelIds(UserAccount viewer) {
        return oAuthTokenRepository.findByUserAndProvider(viewer, OAuthToken.Provider.TWITCH)
            .map(token -> {
                try {
                    String accessToken = tokenEncryptionService.decrypt(token.getAccessToken());
                    ModeratedChannelsResponse response = restClient.get()
                        .uri(TWITCH_API_URL + "/moderation/channels?user_id=" + viewer.getTwitchId())
                            .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
                            .header(CLIENT_ID, twitchClientId)
                        .retrieve()
                        .body(ModeratedChannelsResponse.class);
                    return response != null && response.data() != null
                        ? response.data().stream().map(ModeratedChannel::broadcasterId).toList()
                        : List.<String>of();
                } catch (Exception e) {
                    log.warn("Failed to get moderated channels for user {}: {}", viewer.getId(), e.getMessage());
                    return List.<String>of();
                }
            })
            .orElse(List.of());
    }

    record ModeratedChannelsResponse(List<ModeratedChannel> data) {}
    record ModeratedChannel(@JsonProperty("broadcaster_id") String broadcasterId) {}
}
