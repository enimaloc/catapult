package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final RestClient restClient;
    private final TwitchCategoryService twitchCategoryService;
    private final TwitchTokenService twitchTokenService;
    private final ExternalApiObservations apiObservations;

    public static final String CLIENT_ID = "Client-Id";
    public static final String AUTHORIZATION = "Authorization";
    public static final String AUTHORIZATION_BEARER = "Bearer ";

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Override
    public void updateChannel(UserAccount user, GameBinding binding) {
        apiObservations.observeRun("twitch", "update_channel", () -> {
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
        });
    }

    private void patchChannel(UserAccount user, String accessToken, Map<String, Object> body) {
        restClient.patch()
            .uri(TWITCH_API_URL + "/channels?broadcaster_id=" + user.getTwitchId())
            .header(AUTHORIZATION, AUTHORIZATION_BEARER + accessToken)
            .header(CLIENT_ID, twitchClientId)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    private void doUpdateChannel(UserAccount user, GameBinding binding, OAuthToken token) {
        String accessToken = twitchTokenService.resolveAccessToken(token, user);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", binding.getTwitchGameId());

        UserSettings userSettings = userSettingsRepository.findById(user.getId()).orElse(null);
        boolean globalCclEnabled = userSettings != null ? userSettings.isCclFeatureEnabled() : true;
        if (globalCclEnabled && binding.isCclEnabled()) {
            // Only apply the blocklist to persisted bindings; synthetic fallback bindings (id == null) bypass it
            Set<String> blocked = (userSettings != null && binding.getId() != null) ? userSettings.getBlockedCcls() : Set.of();
            body.put("content_classification_labels", buildCclPayload(binding.getCcls(), blocked));
        }

        try {
            patchChannel(user, accessToken, body);
            log.info("Twitch channel updated for user {} — game_id={}, ccls={}",
                user.getId(), binding.getTwitchGameId(), binding.getCcls());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                String refreshed = twitchTokenService.refreshAccessToken(token, user);
                if (refreshed == null) {
                    log.warn("Twitch token invalid for user {} — pausing bot", user.getId());
                    user.setBotEnabled(false);
                    userAccountRepository.save(user);
                    return;
                }
                try {
                    patchChannel(user, refreshed, body);
                    log.info("Twitch channel updated for user {} after token refresh — game_id={}",
                        user.getId(), binding.getTwitchGameId());
                } catch (Exception retryEx) {
                    log.warn("Twitch channel update failed for user {} after token refresh — pausing bot: {}", user.getId(), retryEx.getMessage());
                    user.setBotEnabled(false);
                    userAccountRepository.save(user);
                }
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
        return apiObservations.observe("twitch", "find_category_by_name", () -> {
            String accessToken = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
                .map(t -> twitchTokenService.resolveAccessToken(t, user))
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
        });
    }

    private static String normalizeTitle(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @Override
    public List<TwitchCategory> searchCategories(UserAccount user, String query) {
        return apiObservations.observe("twitch", "search_categories", () ->
            twitchCategoryService.searchCategories(query));
    }

    // Twitch broadcaster-settable CCLs (MatureGame is set automatically by Twitch, not included)
    private static final List<String> EDITABLE_CCL_IDS = List.of(
        "ViolentGraphic", "SexualThemes", "DrugsIntoxication", "Gambling", "ProfanityVulgarity"
    );

    private List<Map<String, Object>> buildCclPayload(Set<String> cclIds, Set<String> blockedCcls) {
        return EDITABLE_CCL_IDS.stream()
            .map(id -> Map.<String, Object>of("id", id, "is_enabled", cclIds.contains(id) && !blockedCcls.contains(id)))
            .toList();
    }

    @Override
    public void resetToDefault(UserAccount user) {
        apiObservations.observeRun("twitch", "reset_to_default", () ->
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
            })
        );
    }

    private void doResetToDefault(UserAccount user, UserSettings settings, OAuthToken token) {
        String accessToken = twitchTokenService.resolveAccessToken(token, user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", settings.getNoGameTwitchGameId());
        if (settings.isCclFeatureEnabled() && !settings.getNoGameCcls().isEmpty()) {
            body.put("content_classification_labels", buildCclPayload(settings.getNoGameCcls(), Set.of()));
        }
        try {
            patchChannel(user, accessToken, body);
            log.info("Twitch channel reset to default for user {} — game_id={}, ccls={}",
                user.getId(), settings.getNoGameTwitchGameId(), settings.getNoGameCcls());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                String refreshed = twitchTokenService.refreshAccessToken(token, user);
                if (refreshed == null) {
                    log.warn("Twitch token invalid for user {} during reset — pausing bot", user.getId());
                    user.setBotEnabled(false);
                    userAccountRepository.save(user);
                    return;
                }
                try {
                    patchChannel(user, refreshed, body);
                    log.info("Twitch channel reset to default for user {} after token refresh — game_id={}",
                        user.getId(), settings.getNoGameTwitchGameId());
                } catch (Exception retryEx) {
                    log.warn("Twitch channel reset failed for user {} after token refresh — pausing bot: {}", user.getId(), retryEx.getMessage());
                    user.setBotEnabled(false);
                    userAccountRepository.save(user);
                }
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
        return apiObservations.observe("twitch", "get_moderated_channels", () ->
            oAuthTokenRepository.findByUserAndProvider(viewer, OAuthToken.Provider.TWITCH)
                .map(token -> {
                    try {
                        String accessToken = twitchTokenService.resolveAccessToken(token, viewer);
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
                .orElse(List.of())
        );
    }

    record ModeratedChannelsResponse(List<ModeratedChannel> data) {}
    record ModeratedChannel(@JsonProperty("broadcaster_id") String broadcasterId) {}
}
