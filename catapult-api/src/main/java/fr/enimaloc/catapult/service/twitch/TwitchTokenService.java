package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.site.SiteRole;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TwitchTokenService {

    private static final String TWITCH_TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    /** How long before its announced expiry a cached app token stops being reused. */
    static final Duration APP_TOKEN_MARGIN = Duration.ofSeconds(60);

    /** The app access token and the instant it stops being reused. */
    private record AppToken(String token, Instant reuseUntil) {
    }

    private final OAuthTokenRepository oAuthTokenRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final RestClient restClient;
    private final SiteRole siteRole;
    private final Clock clock;
    private final Object appTokenLock = new Object();
    private volatile AppToken appToken;

    @Autowired
    public TwitchTokenService(OAuthTokenRepository oAuthTokenRepository, TokenEncryptionService tokenEncryptionService,
                              RestClient restClient, SiteRole siteRole) {
        this(oAuthTokenRepository, tokenEncryptionService, restClient, siteRole, Clock.systemUTC());
    }

    TwitchTokenService(OAuthTokenRepository oAuthTokenRepository, TokenEncryptionService tokenEncryptionService,
                       RestClient restClient, SiteRole siteRole, Clock clock) {
        this.oAuthTokenRepository = oAuthTokenRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.restClient = restClient;
        this.siteRole = siteRole;
        this.clock = clock;
    }

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
        siteRole.requireActive("twitch token refresh");
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
            Object scope = response.get("scope");

            token.setAccessToken(tokenEncryptionService.encrypt(newAccess));
            if (newRefresh != null) token.setRefreshToken(tokenEncryptionService.encrypt(newRefresh));
            if (expiresIn != null) token.setExpiresAt(Instant.now().plusSeconds(expiresIn.longValue()));
            if (scope instanceof java.util.List<?> scopes) {
                token.setGrantedScopes(scopes.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(" ")));
            }
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

    /**
     * The app access token (client credentials), reused until {@link #APP_TOKEN_MARGIN} before the expiry the
     * token endpoint announced; a token announced without {@code expires_in} is not reused. Thread-safe: concurrent
     * callers missing the cache share a single token request.
     */
    public String getAppAccessToken() {
        AppToken cached = appToken;
        if (reusable(cached)) {
            return cached.token();
        }
        synchronized (appTokenLock) {
            cached = appToken;
            if (reusable(cached)) {
                return cached.token();
            }
            return fetchAppAccessToken();
        }
    }

    /**
     * Forgets the cached app token when it is {@code rejected} (a Helix 401), so the next
     * {@link #getAppAccessToken()} asks for a fresh one; a token already replaced is left alone.
     */
    public void invalidateAppAccessToken(String rejected) {
        synchronized (appTokenLock) {
            AppToken cached = appToken;
            if (cached != null && cached.token().equals(rejected)) {
                appToken = null;
            }
        }
    }

    private boolean reusable(AppToken cached) {
        return cached != null && clock.instant().isBefore(cached.reuseUntil());
    }

    @SuppressWarnings("unchecked")
    private String fetchAppAccessToken() {
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
        appToken = response.get("expires_in") instanceof Number expiresIn
            ? new AppToken(token, clock.instant().plusSeconds(expiresIn.longValue()).minus(APP_TOKEN_MARGIN))
            : null;
        return token;
    }
}
