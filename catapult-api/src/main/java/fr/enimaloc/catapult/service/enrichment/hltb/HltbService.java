package fr.enimaloc.catapult.service.enrichment.hltb;

import fr.enimaloc.catapult.service.enrichment.EnrichmentCache;
import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import fr.enimaloc.catapult.service.enrichment.EnrichmentSource;
import fr.enimaloc.catapult.service.enrichment.FetchBudget;
import fr.enimaloc.catapult.service.igdb.IgdbClient;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import proto.Game;
import proto.GameTimeToBeat;

import java.util.Locale;
import java.util.Optional;

/**
 * How long a game takes to beat: IGDB's {@code game_time_to_beats} first (official API), HowLongToBeat scraping
 * when IGDB has nothing and {@code catapult.enrichment.hltb.scrape-enabled} allows it. Answers, absent ones
 * included, are cached under {@code igdb:<id>} or, without an IGDB id, {@code name:<normalised name>}.
 */
@Slf4j
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

    private Optional<TimeToBeat> fetch(String igdbId, String name) {
        String token = igdbService.getAppToken();
        boolean igdbReachable = token != null && !token.isBlank();
        if (igdbReachable) {
            Optional<TimeToBeat> fromIgdb = igdbClient.findTimeToBeat(igdbId, token).map(HltbService::fromIgdb)
                    .filter(TimeToBeat::hasAnyDuration);
            if (fromIgdb.isPresent()) {
                return fromIgdb;
            }
        } else {
            log.warn("[HLTB] empty IGDB app token, skipping IGDB for {}", igdbId);
        }
        if (!properties.hltb().scrapeEnabled()) {
            return Optional.empty();
        }
        String searchName = name != null && !name.isBlank() ? name
                : igdbReachable ? igdbName(igdbId, token) : null;
        return searchName == null ? Optional.empty() : scraper.search(searchName);
    }

    private Optional<TimeToBeat> scrape(String name) {
        return properties.hltb().scrapeEnabled() ? scraper.search(name) : Optional.empty();
    }

    private String igdbName(String igdbId, String token) {
        return igdbClient.fetchGameById(igdbId, "name", token).stream()
                .map(Game::getName).filter(n -> !n.isBlank()).findFirst().orElse(null);
    }

    private static TimeToBeat fromIgdb(GameTimeToBeat ttb) {
        return new TimeToBeat(seconds(ttb.getHastily()), seconds(ttb.getNormally()), seconds(ttb.getCompletely()),
                ttb.getCount(), TimeToBeat.Origin.IGDB, null);
    }

    /** IGDB leaves an unknown duration at 0. */
    private static Long seconds(int value) {
        return value > 0 ? (long) value : null;
    }

    /** Lower case, trimmed, whitespace collapsed. */
    static String normalise(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
