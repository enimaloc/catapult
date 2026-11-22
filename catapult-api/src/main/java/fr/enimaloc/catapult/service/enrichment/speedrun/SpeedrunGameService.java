package fr.enimaloc.catapult.service.enrichment.speedrun;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.enrichment.GameNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Speedrun.com games and their world records through the shared cache. A game is found when exactly one search
 * result has the wanted normalised name as its international or Twitch name: none, or several, is absent (a wrong
 * world record is worse than none). Every upstream call must be accepted by the caller's budget (asked once) and by
 * the {@link SpeedrunRateLimiter}, which counts every HTTP request of the call (a records answer without any run
 * needs a second one); the public route also fits in the limiter's public share.
 */
@Service
@RequiredArgsConstructor
public class SpeedrunGameService {

    private final EnrichmentCache cache;
    private final SpeedrunClient client;
    private final SpeedrunRateLimiter limiter;

    /** The speedrun.com game called {@code name}, remembered under that name; empty when absent. */
    public Optional<SpeedrunGame> byName(String name, FetchBudget budget) {
        if (GameNames.normalize(name).isEmpty()) {
            return Optional.empty();
        }
        FetchBudget gate = both(budget, limiter.member());
        return cache.get(EnrichmentSource.SPEEDRUN_GAME, "name:" + GameNames.normalize(name), SpeedrunGame.class,
                gate, () -> resolve(name, client.charging(gate)));
    }

    /** The world records of a speedrun.com game id, for a member's request. */
    public Optional<SpeedrunRecords> records(String gameId, FetchBudget budget) {
        return fetchRecords(gameId, both(budget, limiter.member()));
    }

    /** The world records of a speedrun.com game id, for the public route: it must fit in the public share. */
    public Optional<SpeedrunRecords> publicRecords(String gameId, FetchBudget ipBudget) {
        return fetchRecords(gameId, both(ipBudget, limiter.publicShare()));
    }

    private Optional<SpeedrunRecords> fetchRecords(String gameId, FetchBudget budget) {
        return cache.get(EnrichmentSource.SPEEDRUN_RECORDS, "records:" + gameId, SpeedrunRecords.class, budget,
                () -> client.charging(budget).records(gameId));
    }

    private Optional<SpeedrunGame> resolve(String name, SpeedrunClient charged) {
        String wanted = GameNames.normalize(name);
        List<SpeedrunGame> matches = charged.searchGames(name).stream()
                .filter(game -> wanted.equals(normalized(game.name())) || wanted.equals(normalized(game.twitchName())))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static String normalized(String name) {
        return name == null ? null : GameNames.normalize(name);
    }

    /** The caller's budget (asked once) then the limiter's; the limiter is not charged when the caller refuses. */
    static FetchBudget both(FetchBudget caller, FetchBudget limiterBudget) {
        FetchBudget once = FetchBudget.once(caller);
        return new FetchBudget() {
            private volatile FetchBudget refused = once;

            @Override
            public boolean tryAcquire() {
                if (!once.tryAcquire()) {
                    refused = once;
                    return false;
                }
                if (!limiterBudget.tryAcquire()) {
                    refused = limiterBudget;
                    return false;
                }
                return true;
            }

            @Override
            public Duration retryAfter() {
                return refused.retryAfter();
            }
        };
    }
}
