package fr.enimaloc.catapult.service.enrichment.hltb;

import com.api.igdb.exceptions.RequestException;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import proto.GameTimeToBeat;

import java.util.Optional;

/**
 * How long a game takes to beat, from IGDB's {@code game_time_to_beats} (HowLongToBeat figures served by the official
 * API). A game without an IGDB id is not asked about. Answers, absent ones included, are cached under
 * {@code igdb:<id>}; a failure of IGDB throws instead, so it is never cached as absent.
 */
@Service
@RequiredArgsConstructor
public class HltbService {

    private final EnrichmentCache cache;
    private final IgdbClient igdbClient;
    private final IgdbService igdbService;

    /**
     * The time to beat of a game known by its IGDB id (nullable); empty when IGDB does not know it, or without an
     * id (nothing to ask, nothing cached). {@code name} is kept for the callers' sake and is not used.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when it is not cached and
     *                                                                                 {@code budget} is spent
     */
    public Optional<TimeToBeat> timeToBeat(String igdbId, String name, FetchBudget budget) {
        if (igdbId == null || igdbId.isBlank()) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.HLTB, "igdb:" + igdbId, TimeToBeat.class, budget, () -> fetch(igdbId));
    }

    /** The time to beat of an IGDB game, for the detail route. */
    public Optional<TimeToBeat> byIgdbId(String igdbId, FetchBudget budget) {
        return timeToBeat(igdbId, null, budget);
    }

    /**
     * Throws whenever IGDB could not be asked (no app token, error), so that the cache keeps its last known value
     * instead of recording the game as absent.
     */
    private Optional<TimeToBeat> fetch(String igdbId) {
        String token = igdbService.getAppToken();
        if (token == null || token.isBlank()) {
            throw new IgdbUnavailableException("empty IGDB app token, cannot look up " + igdbId, null);
        }
        try {
            return igdbClient.findTimeToBeat(igdbId, token).map(HltbService::fromIgdb)
                    .filter(TimeToBeat::hasAnyDuration);
        } catch (RequestException e) {
            throw new IgdbUnavailableException("IGDB time to beat failed for " + igdbId, e);
        }
    }

    private static TimeToBeat fromIgdb(GameTimeToBeat ttb) {
        return new TimeToBeat(seconds(ttb.getHastily()), seconds(ttb.getNormally()), seconds(ttb.getCompletely()),
                ttb.getCount());
    }

    /** IGDB leaves an unknown duration at 0. */
    private static Long seconds(int value) {
        return value > 0 ? (long) value : null;
    }

    /** IGDB could not be asked; nothing is known about the game. */
    static final class IgdbUnavailableException extends RuntimeException {
        IgdbUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
