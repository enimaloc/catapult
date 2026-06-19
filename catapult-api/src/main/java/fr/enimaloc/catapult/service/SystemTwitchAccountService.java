package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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

/**
 * Manages the OAuth token of the system bot UserAccount (marked
 * {@code systemAccount=true}) that posts chat-command responses on behalf of
 * streamers when it is mod of their channel.
 * <p>
 * The bot is linked to a Twitch identity via the admin OAuth flow
 * ({@code /admin/members/{id}/bot/link-twitch} → {@code /oauth2/start-bot-link}
 * → {@code /oauth2/authorization/twitch}); the resulting token is stored in
 * {@code oauth_token(user=systemAccount, provider=TWITCH)} by
 * {@link fr.enimaloc.catapult.security.CatapultOAuth2UserService#handleBotLink}.
 * <p>
 * The service is a no-op until that link is performed.
 */
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

    private final UserAccountRepository userAccountRepository;
    private final OAuthTokenRepository tokenRepo;
    private final TokenEncryptionService encryption;
    private final RestClient restClient;

    @Value("${twitch.client-id:}")
    private String twitchClientId;

    @Value("${twitch.client-secret:}")
    private String twitchClientSecret;

    @Value("${twitch.system.mod-cache-ttl-seconds:600}")
    private long modCacheTtlSeconds;

    private final Map<UUID, BotModStatus> modCache = new ConcurrentHashMap<>();

    /**
     * Returns a valid bot access token, refreshing it via the stored refresh
     * token if it's within 60s of expiry. Returns {@code null} if the bot is
     * not yet linked (no system account with a Twitch ID + token).
     */
    public String getAccessToken() {
        Optional<TokenView> view = loadCurrentToken();
        if (view.isEmpty()) return null;
        TokenView v = view.get();
        if (v.expiresAt.isBefore(Instant.now().plusSeconds(60))) {
            return refresh().orElse(null);
        }
        return v.accessToken;
    }

    /**
     * Returns the Twitch user ID of the bot, or {@code null} if not linked.
     */
    public String getSystemTwitchId() {
        return userAccountRepository.findBySystemAccountTrue()
            .map(UserAccount::getTwitchId)
            .orElse(null);
    }

    @Transactional
    public synchronized Optional<String> refresh() {
        Optional<UserAccount> systemAccount = userAccountRepository.findBySystemAccountTrue();
        if (systemAccount.isEmpty() || systemAccount.get().getTwitchId() == null) {
            return Optional.empty();
        }
        Optional<OAuthToken> tokenOpt = tokenRepo.findByUserAndProvider(systemAccount.get(),
            OAuthToken.Provider.TWITCH);
        if (tokenOpt.isEmpty() || tokenOpt.get().getRefreshToken() == null) {
            log.warn("System bot Twitch refresh impossible: no refresh token stored");
            return Optional.empty();
        }
        OAuthToken token = tokenOpt.get();
        String currentRefresh = encryption.decrypt(token.getRefreshToken());
        return callRefreshAndUpdate(token, currentRefresh);
    }

    @SuppressWarnings("unchecked")
    private Optional<String> callRefreshAndUpdate(OAuthToken token, String refreshToken) {
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
                return Optional.empty();
            }
            String access = (String) response.get("access_token");
            String newRefresh = (String) response.get("refresh_token");
            Number expiresIn = (Number) response.get("expires_in");

            token.setAccessToken(encryption.encrypt(access));
            if (newRefresh != null) {
                token.setRefreshToken(encryption.encrypt(newRefresh));
            }
            token.setExpiresAt(Instant.now().plusSeconds(expiresIn.longValue()));
            tokenRepo.save(token);
            log.info("System bot Twitch token refreshed");
            return Optional.of(access);
        } catch (RestClientResponseException e) {
            log.error("System bot Twitch refresh HTTP error: {} {}",
                e.getStatusCode().value(), e.getStatusText());
            return Optional.empty();
        } catch (Exception e) {
            log.error("System bot Twitch refresh failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
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
        String botTwitchId = getSystemTwitchId();
        if (botTwitchId == null) return BotModStatus.ofModded(false);
        try {
            Map<String, Object> response = restClient.get()
                .uri(HELIX_MODERATORS_URL + "?broadcaster_id=" + user.getTwitchId()
                    + "&user_id=" + botTwitchId)
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

    private Optional<TokenView> loadCurrentToken() {
        return userAccountRepository.findBySystemAccountTrue()
            .filter(a -> a.getTwitchId() != null)
            .flatMap(a -> tokenRepo.findByUserAndProvider(a, OAuthToken.Provider.TWITCH))
            .map(t -> new TokenView(
                encryption.decrypt(t.getAccessToken()),
                t.getExpiresAt() != null ? t.getExpiresAt() : Instant.EPOCH));
    }

    private record TokenView(String accessToken, Instant expiresAt) {}
}
