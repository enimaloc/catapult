package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TwitchTokenService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";

    private final OAuthTokenRepository oAuthTokenRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    private final Set<UUID> refreshWarnedUsers = ConcurrentHashMap.newKeySet();

    public String resolveAccessToken(OAuthToken token, UserAccount user) {
        if (token.getExpiresAt() != null && Instant.now().isAfter(token.getExpiresAt())) {
            log.debug("Token for user {} is expired, proactively refreshing", user.getId());
            String refreshed = refreshAccessToken(token, user);
            if (refreshed != null) return refreshed;
        }
        return tokenEncryptionService.decrypt(token.getAccessToken());
    }

    @SuppressWarnings("unchecked")
    public String refreshAccessToken(OAuthToken token, UserAccount user) {
        if (token.getRefreshToken() == null) {
            if (refreshWarnedUsers.add(user.getId())) {
                log.warn("No refresh token stored for user {} — cannot refresh", user.getId());
            }
            return null;
        }
        String refreshToken = tokenEncryptionService.decrypt(token.getRefreshToken());
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            form.add("client_id", twitchClientId);
            form.add("client_secret", twitchClientSecret);
            Map<String, Object> response = restClient.post()
                .uri(TWITCH_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
            if (response == null) return null;

            String newAccess = (String) response.get("access_token");
            if (newAccess == null) return null;
            String newRefresh = (String) response.get("refresh_token");
            Number expiresIn = (Number) response.get("expires_in");

            token.setAccessToken(tokenEncryptionService.encrypt(newAccess));
            if (newRefresh != null) token.setRefreshToken(tokenEncryptionService.encrypt(newRefresh));
            if (expiresIn != null) token.setExpiresAt(Instant.now().plusSeconds(expiresIn.longValue()));
            oAuthTokenRepository.save(token);
            refreshWarnedUsers.remove(user.getId());
            log.debug("Refreshed Twitch token for user {}", user.getId());
            return newAccess;
        } catch (Exception e) {
            if (refreshWarnedUsers.add(user.getId())) {
                log.warn("Failed to refresh Twitch token for user {}: {}", user.getId(), e.getMessage());
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public String getAppAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", twitchClientId);
        form.add("client_secret", twitchClientSecret);
        Map<String, Object> response = restClient.post()
            .uri(TWITCH_TOKEN_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("Empty response from Twitch token endpoint");
        }
        String token = (String) response.get("access_token");
        if (token == null) {
            throw new IllegalStateException("No access_token in Twitch token response");
        }
        return token;
    }
}
