package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserAccountRepository;
import fr.esportline.catapult.repository.UserSettingsRepository;
import fr.esportline.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

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

    @Value("${spring.security.oauth2.client.registration.twitch.client-id:}")
    private String twitchClientId;

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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("game_id", binding.getTwitchGameId());

        boolean cclEnabled = userSettingsRepository.findById(user.getId())
            .map(s -> s.isCclFeatureEnabled())
            .orElse(true);

        if (cclEnabled && !binding.getCcls().isEmpty()) {
            body.put("content_classification_labels", buildCclPayload(binding.getCcls()));
        }

        try {
            restClient.patch()
                .uri(TWITCH_API_URL + "/channels?broadcaster_id=" + user.getTwitchId())
                .header("Authorization", "Bearer " + accessToken)
                .header("Client-Id", twitchClientId)
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

    private List<Map<String, Object>> buildCclPayload(Set<TwitchCcl> ccls) {
        return ccls.stream()
            .map(ccl -> Map.<String, Object>of("id", ccl.name(), "is_enabled", true))
            .collect(Collectors.toList());
    }
}
