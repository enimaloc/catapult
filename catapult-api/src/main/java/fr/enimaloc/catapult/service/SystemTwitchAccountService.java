package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemTwitchAccountService {

    public record BotModStatus(boolean modded, Instant checkedAt) {
        public static BotModStatus ofModded(boolean modded) {
            return new BotModStatus(modded, Instant.now());
        }
    }

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String HELIX_MODERATORS_URL = "https://api.twitch.tv/helix/moderation/moderators";

    private final OAuthTokenRepository tokenRepo;
    private final TokenEncryptionService encryption;
    private final RestClient restClient;

    @Value("${twitch.system.user-id:}")
    private String systemUserId;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    @Value("${twitch.system.refresh-token:}")
    private String seedRefreshToken;

    @Value("${twitch.system.mod-cache-ttl-seconds:600}")
    private long modCacheTtlSeconds;

    private volatile String accessTokenCache;
    private volatile Instant expiresAt = Instant.EPOCH;

    private final Map<UUID, BotModStatus> modCache = new ConcurrentHashMap<>();

    @PostConstruct
    @Transactional
    public void init() {
        Optional<OAuthToken> existing = tokenRepo.findByProviderAndUserIsNull(OAuthToken.Provider.SYSTEM);
        if (existing.isPresent()) {
            accessTokenCache = encryption.decrypt(existing.get().getAccessToken());
            expiresAt = existing.get().getExpiresAt() != null
                ? existing.get().getExpiresAt() : Instant.EPOCH;
            return;
        }
        if (seedRefreshToken == null || seedRefreshToken.isBlank()) {
            log.warn("No system Twitch refresh token configured; system bot disabled");
            return;
        }
        refreshFromSeed();
    }

    public String getAccessToken() {
        if (accessTokenCache != null && Instant.now().isAfter(expiresAt.minusSeconds(60))) {
            refresh();
        }
        return accessTokenCache;
    }

    public String getSystemTwitchId() {
        return systemUserId;
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000L) // every 30 min
    public void refreshIfNeeded() {
        if (accessTokenCache != null && Instant.now().isAfter(expiresAt.minusSeconds(15 * 60))) {
            refresh();
        }
    }

    @Transactional
    public synchronized void refresh() {
        OAuthToken token = tokenRepo.findByProviderAndUserIsNull(OAuthToken.Provider.SYSTEM)
            .orElseGet(this::createSystemTokenRecord);
        String currentRefresh = encryption.decrypt(token.getRefreshToken());
        callRefreshAndUpdate(token, currentRefresh);
    }

    private void refreshFromSeed() {
        OAuthToken token = createSystemTokenRecord();
        callRefreshAndUpdate(token, seedRefreshToken);
    }

    @SuppressWarnings("unchecked")
    private void callRefreshAndUpdate(OAuthToken token, String refreshToken) {
        String body = "grant_type=refresh_token"
            + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
            + "&client_id=" + URLEncoder.encode(twitchClientId, StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(twitchClientSecret, StandardCharsets.UTF_8);
        try {
            Map<String, Object> response = restClient.post()
                .uri(TWITCH_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map.class);
            if (response == null) {
                log.error("System Twitch refresh returned null response");
                return;
            }
            String access = (String) response.get("access_token");
            String newRefresh = (String) response.get("refresh_token");
            Number expiresIn = (Number) response.get("expires_in");

            token.setAccessToken(encryption.encrypt(access));
            token.setRefreshToken(encryption.encrypt(newRefresh));
            token.setExpiresAt(Instant.now().plusSeconds(expiresIn.longValue()));
            tokenRepo.save(token);

            accessTokenCache = access;
            expiresAt = token.getExpiresAt();
            log.info("System Twitch token refreshed");
        } catch (RestClientResponseException e) {
            // Only log the status/reason; never the URI or body to avoid leaking the secret in logs.
            log.error("System Twitch refresh HTTP error: {} {}",
                e.getStatusCode().value(), e.getStatusText());
        } catch (Exception e) {
            log.error("System Twitch refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    private OAuthToken createSystemTokenRecord() {
        OAuthToken t = new OAuthToken();
        t.setProvider(OAuthToken.Provider.SYSTEM);
        t.setUser(null);
        return t;
    }

    public BotModStatus check(UserAccount user, String streamerToken) {
        BotModStatus cached = modCache.get(user.getId());
        if (cached != null && cached.checkedAt()
            .isAfter(Instant.now().minusSeconds(modCacheTtlSeconds))) {
            return cached;
        }
        BotModStatus fresh = fetchModStatus(user, streamerToken);
        modCache.put(user.getId(), fresh);
        return fresh;
    }

    public void invalidateModStatus(UUID userId) {
        modCache.remove(userId);
    }

    @SuppressWarnings("unchecked")
    private BotModStatus fetchModStatus(UserAccount user, String streamerToken) {
        try {
            Map<String, Object> response = restClient.get()
                .uri(HELIX_MODERATORS_URL + "?broadcaster_id=" + user.getTwitchId()
                    + "&user_id=" + systemUserId)
                .header("Authorization", "Bearer " + streamerToken)
                .header("Client-Id", twitchClientId)
                .retrieve()
                .body(Map.class);
            List<?> data = response == null ? List.of() : (List<?>) response.get("data");
            return BotModStatus.ofModded(data != null && !data.isEmpty());
        } catch (Exception e) {
            log.warn("Bot mod status check failed for user {}: {}", user.getId(), e.getMessage());
            return BotModStatus.ofModded(false);
        }
    }
}
