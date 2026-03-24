package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.*;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.repository.UserSettingsRepository;
import fr.esportline.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service d'intégration Twitch — découplé du système d'événements Spring.
 * Reçoit uniquement UserAccount + Binding en paramètres.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwitchService {

    private static final String TWITCH_API_URL = "https://api.twitch.tv/helix";

    private final OAuthTokenRepository oAuthTokenRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    /**
     * Met à jour la catégorie et les CCLs de la chaîne Twitch.
     * Ne fait rien si le binding est INCOMPLETE ou ignored.
     */
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
        try {
            callPatchChannel(user, binding, accessToken);
            log.info("Twitch channel updated for user {} — game_id={}, ccls={}",
                user.getId(), binding.getTwitchGameId(), binding.getCcls());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                refreshToken(user, token).ifPresentOrElse(
                    newAccess -> retryAfterRefresh(user, binding, newAccess),
                    () -> {
                        log.warn("Twitch token invalid and refresh failed for user {} — pausing bot", user.getId());
                        pauseBot(user);
                    }
                );
            } else if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Twitch rate limit hit for user {} — will retry next cycle", user.getId());
            } else {
                log.error("Twitch API error for user {}: {} {}", user.getId(), e.getStatusCode(), e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error updating Twitch channel for user {}", user.getId(), e);
        }
    }

    private void retryAfterRefresh(UserAccount user, GameBinding binding, String accessToken) {
        try {
            callPatchChannel(user, binding, accessToken);
            log.info("Twitch channel updated after token refresh for user {}", user.getId());
        } catch (HttpClientErrorException e) {
            log.warn("Twitch channel update failed after refresh for user {}: {} — pausing bot",
                user.getId(), e.getStatusCode());
            pauseBot(user);
        }
    }

    private void callPatchChannel(UserAccount user, GameBinding binding, String accessToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", binding.getTwitchGameId());

        boolean globalCclEnabled = userSettingsRepository.findById(user.getId())
            .map(UserSettings::isCclFeatureEnabled)
            .orElse(true);

        if (globalCclEnabled && binding.isCclEnabled()) {
            body.put("content_classification_labels", buildCclPayload(binding.getCcls()));
        }

        restClient.patch()
            .uri(TWITCH_API_URL + "/channels?broadcaster_id=" + user.getTwitchId())
            .header("Authorization", "Bearer " + accessToken)
            .header("Client-ID", twitchClientId)
            .header("Content-Type", "application/json")
            .body(body)
            .retrieve()
            .toBodilessEntity();
    }

    private void pauseBot(UserAccount user) {
        user.setBotEnabled(false);
        userAccountRepository.save(user);
    }

    /**
     * Calls POST /oauth2/token with grant_type=refresh_token.
     * Persists the new access + refresh tokens on success.
     * Returns the new access token, or empty if refresh failed.
     */
    @SuppressWarnings("unchecked")
    private Optional<String> refreshToken(UserAccount user, OAuthToken token) {
        if (token.getRefreshToken() == null || token.getRefreshToken().isBlank()) {
            log.warn("No refresh token stored for user {} — cannot refresh Twitch token", user.getId());
            return Optional.empty();
        }

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "refresh_token");
        params.add("refresh_token", tokenEncryptionService.decrypt(token.getRefreshToken()));
        params.add("client_id", twitchClientId);
        params.add("client_secret", twitchClientSecret);

        try {
            Map<String, Object> response = restClient.post()
                .uri("https://id.twitch.tv/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params)
                .retrieve()
                .body(Map.class);

            if (response == null || !response.containsKey("access_token")) {
                log.warn("Twitch token refresh returned empty response for user {}", user.getId());
                return Optional.empty();
            }

            String newAccess  = (String) response.get("access_token");
            String newRefresh = (String) response.get("refresh_token");
            Number expiresIn  = (Number) response.get("expires_in");

            token.setAccessToken(tokenEncryptionService.encrypt(newAccess));
            if (newRefresh != null) {
                token.setRefreshToken(tokenEncryptionService.encrypt(newRefresh));
            }
            if (expiresIn != null) {
                token.setExpiresAt(Instant.now().plusSeconds(expiresIn.longValue()));
            }
            oAuthTokenRepository.save(token);

            log.info("Twitch token refreshed for user {}", user.getId());
            return Optional.of(newAccess);

        } catch (Exception e) {
            log.warn("Twitch token refresh failed for user {}: {}", user.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Résout l'ID catégorie Twitch par nom exact (GET /helix/games?name=...).
     * Utilisé en fallback quand l'ID IGDB externe n'est pas disponible.
     */
    @SuppressWarnings("unchecked")
    public Optional<String> findCategoryIdByName(UserAccount user, String gameName) {
        String accessToken = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .map(t -> tokenEncryptionService.decrypt(t.getAccessToken()))
            .orElse("");

        if (accessToken.isBlank() || twitchClientId.isBlank()) return Optional.empty();

        try {
            Map<String, Object> response = restClient.get()
                .uri(TWITCH_API_URL + "/games?name=" +
                     java.net.URLEncoder.encode(gameName, java.nio.charset.StandardCharsets.UTF_8))
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(Map.class);

            if (response == null) return Optional.empty();
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return Optional.empty();

            return Optional.ofNullable((String) data.get(0).get("id"));
        } catch (Exception e) {
            log.warn("Twitch game lookup failed for '{}': {}", gameName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Recherche des catégories Twitch par nom (pour l'autocomplete des bindings).
     */
    @SuppressWarnings("unchecked")
    public List<TwitchCategory> searchCategories(UserAccount user, String query) {
        String accessToken = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)
            .map(t -> tokenEncryptionService.decrypt(t.getAccessToken()))
            .orElse("");

        if (accessToken.isBlank() || twitchClientId.isBlank()) return List.of();

        try {
            Map<String, Object> response = restClient.get()
                .uri(TWITCH_API_URL + "/search/categories?query=" +
                     java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) + "&first=8")
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(Map.class);

            if (response == null) return List.of();

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null) return List.of();

            return data.stream()
                .map(g -> new TwitchCategory((String) g.get("id"), (String) g.get("name")))
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Twitch category search failed for query '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    public record TwitchCategory(String id, String name) {}

    // Twitch broadcaster-settable CCLs (MatureGame is set automatically by Twitch, not included)
    private static final List<String> EDITABLE_CCL_IDS = List.of(
        "ViolentGraphic", "SexualThemes", "DrugsIntoxication", "Gambling", "ProfanityVulgarity"
    );

    private List<Map<String, Object>> buildCclPayload(Set<String> cclIds) {
        return EDITABLE_CCL_IDS.stream()
            .map(id -> Map.<String, Object>of("id", id, "is_enabled", cclIds.contains(id)))
            .collect(Collectors.toList());
    }
}
