package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
import fr.enimaloc.catapult.repository.speedrun.SpeedrunAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The personal bests and the runs waiting for verification of a speedrun.com account, full-game only, through the
 * shared cache ({@code speedrun-pbs} / {@code pb:<account>:<game|all>}, {@code speedrun-pending} /
 * {@code pending:<account>:<game|all>}). The key is speedrun.com's account id, not the member: both are public at
 * speedrun.com, so two members pointing at one account share the entry. Every upstream call asks the caller's
 * budget then the {@link SpeedrunRateLimiter}'s member share. An empty answer means unknown (the source is off),
 * an empty list means none.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeedrunPersonalService {

    private static final String ALL_GAMES = "all";

    private final EnrichmentCache cache;
    private final SpeedrunClient client;
    private final SpeedrunRateLimiter limiter;
    private final SpeedrunAccountRepository accounts;
    private final TokenEncryptionService encryption;
    private final EnrichmentProperties properties;

    /** Whether a member's speedrun.com account and personal bests can be looked up at all. */
    public boolean enabled() {
        return properties.source(EnrichmentSource.SPEEDRUN_USER).enabled()
                && properties.source(EnrichmentSource.SPEEDRUN_PBS).enabled();
    }

    /** The personal bests of the account {@code srcUserId} on one game, or on all of them when it is null. */
    public Optional<List<SpeedrunRun>> personalBests(String srcUserId, String gameIdOrNull, FetchBudget budget) {
        FetchBudget gate = SpeedrunGameService.both(budget, limiter.member());
        return cache.get(EnrichmentSource.SPEEDRUN_PBS, "pb:" + srcUserId + ":" + game(gameIdOrNull), Runs.class,
                        gate, () -> Optional.of(new Runs(client.charging(gate).personalBests(srcUserId, gameIdOrNull))))
                .map(Runs::runs);
    }

    /**
     * The runs of {@code account} waiting for verification, on one game or on all of them. They are public at
     * speedrun.com: {@code apiKey}, when given, is only sent along (never cached nor logged).
     */
    public Optional<List<SpeedrunRun>> pendingRuns(SpeedrunIdentity account, String gameIdOrNull,
                                                   Optional<String> apiKey, FetchBudget budget) {
        FetchBudget gate = SpeedrunGameService.both(budget, limiter.member());
        return cache.get(EnrichmentSource.SPEEDRUN_PENDING, "pending:" + account.userId() + ":" + game(gameIdOrNull),
                        Runs.class, gate,
                        () -> Optional.of(new Runs(client.charging(gate).pendingRuns(account.userId(), gameIdOrNull,
                                apiKey.orElse(null)))))
                .map(Runs::runs);
    }

    /**
     * The member's personal key, decrypted, when {@code account} was found by that key and speedrun.com has not
     * refused it; empty otherwise (another account's key is never sent).
     */
    public Optional<String> apiKey(UserAccount user, SpeedrunIdentity account) {
        if (account.source() != SpeedrunIdentity.Source.API_KEY) {
            return Optional.empty();
        }
        return accounts.findById(user.getId())
                .filter(row -> row.getApiKeyEnc() != null && !row.isKeyInvalid())
                .filter(row -> account.userId().equals(row.getKeyUserId()))
                .flatMap(this::decrypt);
    }

    private Optional<String> decrypt(SpeedrunAccount row) {
        try {
            return Optional.of(encryption.decrypt(row.getApiKeyEnc()));
        } catch (RuntimeException e) {
            log.warn("speedrun.com key of {} not decrypted: {}", row.getUserId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String game(String gameIdOrNull) {
        return gameIdOrNull == null ? ALL_GAMES : gameIdOrNull;
    }

    /** The cached shape of a list of runs (the cache stores one object per key). */
    public record Runs(List<SpeedrunRun> runs) {
    }
}
