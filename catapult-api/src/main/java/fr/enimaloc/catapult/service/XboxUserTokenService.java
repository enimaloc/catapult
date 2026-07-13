package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
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
 * Fournit un token XSTS (relying party Xbox Live) valide par utilisateur, en
 * déroulant la chaîne refresh Microsoft → Xbox user token → XSTS. Contrairement
 * à {@link MinecraftTokenService}, il n'y a pas de compte de service : chaque
 * utilisateur interroge sa propre présence avec son propre token OAuth2 lié
 * (provider {@link OAuthToken.Provider#XBOX}).
 * <p>
 * Le refresh token Microsoft tourne à chaque renouvellement et est re-persisté
 * chiffré. Verrou de rafraîchissement par utilisateur (clé = UUID) afin qu'un
 * refresh lent sur un compte ne bloque pas les autres.
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
    // 1 = token OK, 0 = chaîne en échec ; par UUID d'utilisateur
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
                .description("Nombre d'utilisateurs Xbox avec un token XSTS valide")
                .register(meterRegistry);
    }

    /** Token XSTS scopé Xbox Live, prêt pour l'API de présence : header {@code XBL3.0 x={userHash};{token}}. */
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
            // double-check après acquisition du lock : un autre thread a pu rafraîchir
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
                log.warn("Chaîne d'auth Xbox en échec pour l'utilisateur {}: {}", user.getId(), e.getMessage());
                return Optional.empty();
            }
        }
    }

    /** Rafraîchit le token Microsoft via le mécanisme standard Spring Security, re-persiste le refresh token roté. */
    private String refreshMicrosoftToken(OAuthToken stored) {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        if (registration == null) {
            throw new IllegalStateException("ClientRegistration '" + REGISTRATION_ID + "' absente");
        }

        String decryptedRefreshToken = encryption.decrypt(stored.getRefreshToken());
        // Placeholder : seul le refresh token compte pour cette requête, mais l'API exige un access token existant.
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
