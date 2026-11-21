package fr.enimaloc.catapult.service.enrichment.hltb;

import com.api.igdb.exceptions.RequestException;
import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import proto.Game;
import proto.GameTimeToBeat;

import java.util.Locale;
import java.util.Optional;

/**
 * How long a game takes to beat: IGDB's {@code game_time_to_beats} first (official API), HowLongToBeat scraping
 * when IGDB has nothing and {@code catapult.enrichment.hltb.scrape.enabled} allows it. Answers, absent ones
 * included, are cached under {@code igdb:<id>} or, without an IGDB id, {@code name:<normalised name>}; a failure
 * of either source throws instead, so it is never cached as absent.
 */
@Service
@RequiredArgsConstructor
public class HltbService {

    private final EnrichmentCache cache;
    private final IgdbClient igdbClient;
    private final IgdbService igdbService;
    private final HltbScraper scraper;
    private final EnrichmentProperties properties;

    /**
     * The time to beat of a game known by its IGDB id (nullable) and its name; empty when neither source knows it.
     *
     * @throws fr.enimaloc.catapult.service.enrichment.UpstreamBudgetExceededException when it is not cached and
     *                                                                                 {@code budget} is spent
     */
    public Optional<TimeToBeat> timeToBeat(String igdbId, String name, FetchBudget budget) {
        if (igdbId != null && !igdbId.isBlank()) {
            return cache.get(EnrichmentSource.HLTB, "igdb:" + igdbId, TimeToBeat.class, budget,
                    () -> fetch(igdbId, name));
        }
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return cache.get(EnrichmentSource.HLTB, "name:" + normalise(name), TimeToBeat.class, budget,
                () -> scrape(name));
    }

    /** The time to beat of an IGDB game, for the detail route; its name is asked from IGDB if scraping is needed. */
    public Optional<TimeToBeat> byIgdbId(String igdbId, FetchBudget budget) {
        return timeToBeat(igdbId, null, budget);
    }

    /**
     * IGDB first, then the scraper. Throws whenever IGDB could not be asked (no app token, error, name lookup
     * failed), so that the cache keeps its last known value instead of recording the game as absent.
     */
    private Optional<TimeToBeat> fetch(String igdbId, String name) {
        String token = igdbService.getAppToken();
        if (token == null || token.isBlank()) {
            throw new IgdbUnavailableException("empty IGDB app token, cannot look up " + igdbId, null);
        }
        Optional<TimeToBeat> fromIgdb;
        try {
            fromIgdb = igdbClient.findTimeToBeat(igdbId, token).map(HltbService::fromIgdb)
                    .filter(TimeToBeat::hasAnyDuration);
        } catch (RequestException e) {
            throw new IgdbUnavailableException("IGDB time to beat failed for " + igdbId, e);
        }
        if (fromIgdb.isPresent()) {
            return fromIgdb;
        }
        if (!properties.hltb().scrapeEnabled()) {
            return Optional.empty();
        }
        return scraper.search(name != null && !name.isBlank() ? name : igdbName(igdbId, token));
    }

    private Optional<TimeToBeat> scrape(String name) {
        return properties.hltb().scrapeEnabled() ? scraper.search(name) : Optional.empty();
    }

    /** The IGDB name of the game; IGDB answering nothing counts as a failure (its lookup hides errors). */
    private String igdbName(String igdbId, String token) {
        return igdbClient.fetchGameById(igdbId, "name", token).stream()
                .map(Game::getName).filter(n -> !n.isBlank()).findFirst()
                .orElseThrow(() -> new IgdbUnavailableException("no IGDB name for " + igdbId, null));
    }

    private static TimeToBeat fromIgdb(GameTimeToBeat ttb) {
        return new TimeToBeat(seconds(ttb.getHastily()), seconds(ttb.getNormally()), seconds(ttb.getCompletely()),
                ttb.getCount(), TimeToBeat.Origin.IGDB, null);
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

    /** Lower case, trimmed, whitespace collapsed. */
    static String normalise(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
