package fr.enimaloc.catapult.service.minecraft;

import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.xbox.XboxService;
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
 * Provides a valid Minecraft access token per service account, by walking
 * the chain MSA refresh token → rpsTicket → Xbox user token → XSTS → Minecraft.
 * The token (24 h) is cached in memory and renewed when less than an hour of
 * validity remains. The MSA refresh token rotates on every renewal and is
 * persisted again, encrypted.
 * <p>
 * The refresh lock is per service account (key = UUID) so that a slow refresh
 * on one account does not block the others.
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
    // 1 = token OK, 0 = chain failing; per account UUID
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
                .description("Number of Minecraft service accounts with a valid token")
                .register(meterRegistry);
    }

    public Optional<String> getToken(MinecraftServiceAccount account) {
        if (account.getId() == null) {
            // transient account (enrolment validation): no cache, no lock, no persistence
            return validateChain(account.getMsaRefreshToken()).map(ValidatedChain::minecraftToken);
        }
        CachedToken cached = cache.get(account.getId());
        if (cached != null && cached.expiry().isAfter(Instant.now().plus(RENEW_BEFORE_EXPIRY))) {
            return Optional.of(cached.token());
        }
        return refreshChain(account);
    }

    /**
     * Walks the whole chain with no side effect (no cache, no lock, no DB write),
     * to validate an account not yet persisted at enrolment. The rotated (encrypted)
     * refresh token is returned to the caller, which has to store it.
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
            log.warn("Minecraft auth chain failed: {}", e.getMessage());
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
            // double-check after acquiring the lock: another thread may have refreshed
            CachedToken cached = cache.get(account.getId());
            if (cached != null && cached.expiry().isAfter(Instant.now().plus(RENEW_BEFORE_EXPIRY))) {
                return Optional.of(cached.token());
            }
            Optional<ValidatedChain> chain = validateChain(account.getMsaRefreshToken());
            if (chain.isEmpty()) {
                tokenState.put(account.getId(), 0);
                log.warn("Minecraft auth chain failed for account {}", account.getLabel());
                return Optional.empty();
            }

            // the rotation is persisted once the whole chain has succeeded
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
