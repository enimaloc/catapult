package fr.enimaloc.catapult.service.xbox;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.minecraft.MinecraftTokenService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a valid XSTS token (Xbox Live relying party) per user, by walking
 * the chain Microsoft refresh → Xbox user token → XSTS. Unlike
 * {@link MinecraftTokenService}, there is no service account: each
 * user queries their own presence with their own linked OAuth2 token
 * (provider {@link OAuthToken.Provider#XBOX}).
 * <p>
 * The Microsoft refresh token rotates on every renewal and is persisted again,
 * encrypted. The refresh lock is per user (key = UUID) so that a slow
 * refresh on one account does not block the others.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("xbox.enabled")
public class XboxUserTokenService {

    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofMinutes(5);
    private static final String REGISTRATION_ID = "xbox";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final TokenEncryptionService encryption;
    private final XboxService xboxService;
    private final OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient;

    private final Map<UUID, CachedSession> cache = new ConcurrentHashMap<>();
    // 1 = token OK, 0 = chain failing; per user UUID
    private final Map<UUID, Integer> tokenState = new ConcurrentHashMap<>();
    private final Map<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

    public XboxUserTokenService(ClientRegistrationRepository clientRegistrationRepository,
                                 OAuthTokenRepository oAuthTokenRepository,
                                 TokenEncryptionService encryption,
                                 XboxService xboxService,
                                 OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient,
                                 MeterRegistry meterRegistry) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.oAuthTokenRepository = oAuthTokenRepository;
        this.encryption = encryption;
        this.xboxService = xboxService;
        this.refreshClient = refreshClient;
        Gauge.builder("catapult.xbox.token.ok",
                        tokenState, m -> m.values().stream().mapToInt(Integer::intValue).sum())
                .description("Number of Xbox users with a valid XSTS token")
                .register(meterRegistry);
    }

    /** XSTS token scoped to Xbox Live, ready for the presence API: header {@code XBL3.0 x={userHash};{token}}. */
    public record XstsSession(String token, String userHash, String xuid) {}

    public Optional<XstsSession> getToken(UserAccount user) {
        CachedSession cached = cache.get(user.getId());
        if (cached != null && cached.expiry().isAfter(Instant.now().plus(RENEW_BEFORE_EXPIRY))) {
            return Optional.of(cached.session());
        }
        return refreshChain(user);
    }

    public void evict(UUID userId) {
        cache.remove(userId);
        tokenState.remove(userId);
        refreshLocks.remove(userId);
    }

    private Optional<XstsSession> refreshChain(UserAccount user) {
        synchronized (refreshLocks.computeIfAbsent(user.getId(), id -> new Object())) {
            // double-check after acquiring the lock: another thread may have refreshed
            CachedSession cached = cache.get(user.getId());
            if (cached != null && cached.expiry().isAfter(Instant.now().plus(RENEW_BEFORE_EXPIRY))) {
                return Optional.of(cached.session());
            }

            Optional<OAuthToken> tokenOpt = oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.XBOX);
            if (tokenOpt.isEmpty() || tokenOpt.get().getRefreshToken() == null) {
                tokenState.put(user.getId(), 0);
                return Optional.empty();
            }

            try {
                String msaAccessToken = refreshMicrosoftToken(tokenOpt.get());
                XboxService.Token xbox = xboxService.getXboxToken("d=" + msaAccessToken);
                XboxService.Token xsts = xboxService.getXstsToken(XboxService.XBOX_LIVE_RELYING_PARTY, xbox);
                XboxService.DisplayClaims.Xui claim = xsts.displayClaims().xui()[0];

                XstsSession session = new XstsSession(xsts.token(), claim.uhs(), claim.xid());
                cache.put(user.getId(), new CachedSession(session, xsts.notAfter()));
                tokenState.put(user.getId(), 1);
                return Optional.of(session);
            } catch (Exception e) {
                tokenState.put(user.getId(), 0);
                log.warn("Xbox auth chain failed for user {}: {}", user.getId(), e.getMessage());
                return Optional.empty();
            }
        }
    }

    /** Refreshes the Microsoft token through the standard Spring Security mechanism and persists the rotated refresh token. */
    private String refreshMicrosoftToken(OAuthToken stored) {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            throw new IllegalStateException("ClientRegistration '" + REGISTRATION_ID + "' is missing");
        }

        String decryptedRefreshToken = encryption.decrypt(stored.getRefreshToken());
        // Placeholder: only the refresh token matters for this request, but the API requires an existing access token.
        OAuth2AccessToken placeholderAccessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "placeholder", Instant.now().minusSeconds(3600), Instant.now());
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(decryptedRefreshToken, Instant.now());

        OAuth2AccessTokenResponse response = refreshClient.getTokenResponse(
                new OAuth2RefreshTokenGrantRequest(registration, placeholderAccessToken, refreshToken));

        stored.setAccessToken(encryption.encrypt(response.getAccessToken().getTokenValue()));
        if (response.getRefreshToken() != null) {
            stored.setRefreshToken(encryption.encrypt(response.getRefreshToken().getTokenValue()));
        }
        stored.setExpiresAt(response.getAccessToken().getExpiresAt());
        oAuthTokenRepository.save(stored);

        return response.getAccessToken().getTokenValue();
    }

    private record CachedSession(XstsSession session, Instant expiry) {}
}
