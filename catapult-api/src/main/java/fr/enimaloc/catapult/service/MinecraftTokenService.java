package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fournit un access token Minecraft valide par compte de service, en déroulant
 * la chaîne MSA refresh token → rpsTicket → Xbox user token → XSTS → Minecraft.
 * Le token (24 h) est caché en mémoire et renouvelé quand il reste moins
 * d'une heure de validité. Le refresh token MSA tourne à chaque renouvellement
 * et est re-persisté chiffré.
 * <p>
 * Le verrou de rafraîchissement est par compte de service (clé = UUID) afin
 * qu'un refresh lent sur un compte ne bloque pas les autres.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MinecraftTokenService {

    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofHours(1);

    private final MsaAuthClient msaAuthClient;
    private final XboxService xboxService;
    private final MinecraftService minecraftService;
    private final TokenEncryptionService encryption;
    private final MinecraftServiceAccountRepository accountRepository;

    private final Map<UUID, CachedToken> cache = new ConcurrentHashMap<>();
    // 1 = token OK, 0 = chaîne en échec ; par UUID de compte
    private final Map<UUID, Integer> tokenState = new ConcurrentHashMap<>();
    private final Map<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

    public MinecraftTokenService(MsaAuthClient msaAuthClient,
                                 XboxService xboxService,
                                 MinecraftService minecraftService,
                                 TokenEncryptionService encryption,
                                 MinecraftServiceAccountRepository accountRepository,
                                 MeterRegistry meterRegistry) {
        this.msaAuthClient = msaAuthClient;
        this.xboxService = xboxService;
        this.minecraftService = minecraftService;
        this.encryption = encryption;
        this.accountRepository = accountRepository;
        Gauge.builder("catapult.minecraft.token.ok",
                        tokenState, m -> m.values().stream().mapToInt(Integer::intValue).sum())
                .description("Nombre de comptes de service Minecraft avec un token valide")
                .register(meterRegistry);
    }

    public Optional<String> getToken(MinecraftServiceAccount account) {
        if (account.getId() == null) {
            // compte transient (validation d'enrôlement) : ni cache, ni verrou, ni persistance
            return validateChain(account.getMsaRefreshToken()).map(ValidatedChain::minecraftToken);
        }
        CachedToken cached = cache.get(account.getId());
        if (cached != null && cached.expiry().isAfter(Instant.now().plus(RENEW_BEFORE_EXPIRY))) {
            return Optional.of(cached.token());
        }
        return refreshChain(account);
    }

    /**
     * Déroule la chaîne complète sans aucun effet de bord (pas de cache, pas de
     * verrou, pas d'écriture en base) — utilisé pour valider un compte pas encore
     * persisté à l'enrôlement. Le refresh token rotaté (chiffré) est retourné à
     * l'appelant, à lui de le stocker.
     */
    public Optional<ValidatedChain> validateChain(String encryptedRefreshToken) {
        try {
            MsaAuthClient.MsaTokens msa = msaAuthClient.refresh(encryption.decrypt(encryptedRefreshToken));
            XboxService.Token xbox = xboxService.getXboxToken("d=" + msa.accessToken());
            XboxService.Token xsts = xboxService.getXstsToken(xbox);
            MinecraftService.Token mc = minecraftService.getMinecraftToken(xsts);
            return Optional.of(new ValidatedChain(
                    mc.accessToken(), encryption.encrypt(msa.refreshToken()), mc.expiresIn()));
        } catch (Exception e) {
            log.warn("Chaîne d'auth Minecraft en échec: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public record ValidatedChain(String minecraftToken, String rotatedRefreshTokenEncrypted, long expiresIn) {}

    public void evict(UUID accountId) {
        cache.remove(accountId);
        tokenState.remove(accountId);
        refreshLocks.remove(accountId);
    }

    private Optional<String> refreshChain(MinecraftServiceAccount account) {
        synchronized (refreshLocks.computeIfAbsent(account.getId(), id -> new Object())) {
            // double-check après acquisition du lock : un autre thread a pu rafraîchir
            CachedToken cached = cache.get(account.getId());
            if (cached != null && cached.expiry().isAfter(Instant.now().plus(RENEW_BEFORE_EXPIRY))) {
                return Optional.of(cached.token());
            }
            Optional<ValidatedChain> chain = validateChain(account.getMsaRefreshToken());
            if (chain.isEmpty()) {
                tokenState.put(account.getId(), 0);
                log.warn("Chaîne d'auth Minecraft en échec pour le compte {}", account.getLabel());
                return Optional.empty();
            }

            // rotation persistée après le succès de la chaîne complète
            account.setMsaRefreshToken(chain.get().rotatedRefreshTokenEncrypted());
            account.setUpdatedAt(Instant.now());
            accountRepository.save(account);

            cache.put(account.getId(),
                    new CachedToken(chain.get().minecraftToken(), Instant.now().plusSeconds(chain.get().expiresIn())));
            tokenState.put(account.getId(), 1);
            return Optional.of(chain.get().minecraftToken());
        }
    }

    private record CachedToken(String token, Instant expiry) {}
}
