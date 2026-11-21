package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.api.userapi.MoreLinks;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
import fr.enimaloc.catapult.service.enrichment.trailer.TrailerSourceResolver;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.link.ExternalLinks;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Asks the five enrichment sources (HowLongToBeat, ProtonDB, SteamGridDB, Steam Deck, PCGamingWiki) and picks the
 * trailer link of a detected game, all at once on virtual threads, and waits for them at most
 * {@code catapult.enrichment.aggregate-timeout}. A source that fails is null; one that is too late is null too but
 * keeps running in the background (never interrupted), so its answer lands in the cache for the next call.
 *
 * <p>The Steam-keyed sources use the detected Steam app id, else IGDB's Steam mapping of the game; without one,
 * ProtonDB and Steam Deck are skipped and SteamGridDB and PCGamingWiki search by name.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameEnrichmentAssembler {

    /** The route that streams the current game's trailer as MP4, relative to the public base URL. */
    static final String CURRENT_GAME_TRAILER_PATH = "/api/me/game/trailer.mp4";

    private final HltbService hltbService;
    private final ProtonDbService protonDbService;
    private final SteamGridDbService steamGridDbService;
    private final SteamDeckService steamDeckService;
    private final PcGamingWikiService pcGamingWikiService;
    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final TrailerSourceResolver trailerSourceResolver;
    private final EnrichmentProperties properties;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Everything the sources know of {@code detected}, within the aggregate timeout. {@code steamAppId} is the
     * detected Steam app id (empty for another store: IGDB's mapping is looked up from {@code igdbId}), {@code name}
     * the name to search by when there is no id, {@code links} the origin the trailer link points at.
     */
    public GameEnrichment enrich(DetectedGame detected, Optional<String> igdbId, Optional<String> steamAppId,
                                 String name, MoreLinks links) {
        long deadline = System.nanoTime() + properties.aggregateTimeout().toNanos();
        String igdb = igdbId.filter(id -> !id.isBlank()).orElse(null);

        CompletableFuture<String> appId = steamAppId.filter(id -> !id.isBlank())
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> supply("steam app id", () -> igdb == null ? null
                        : igdbService.findExternalUid(igdb, GameBinding.SourceType.STEAM).orElse(null)));

        var hltb = supply("hltb", () -> hltbService.timeToBeat(igdb, FetchBudget.UNLIMITED).orElse(null));
        var protondb = after(appId, "protondb", id -> id == null ? null
                : protonDbService.summary(id, FetchBudget.UNLIMITED).orElse(null));
        var steamDeck = after(appId, "steamdeck", id -> id == null ? null
                : steamDeckService.compatibility(id, FetchBudget.UNLIMITED).orElse(null));
        var steamgriddb = after(appId, "steamgriddb", id -> steamGridDb(id, name));
        var pcgamingwiki = after(appId, "pcgamingwiki", id -> pcGamingWiki(id, name));
        var trailer = after(appId, "trailer", id -> trailer(detected, id, igdb, links));

        return new GameEnrichment(
                await(hltb, deadline, "hltb"),
                await(protondb, deadline, "protondb"),
                await(steamgriddb, deadline, "steamgriddb"),
                await(steamDeck, deadline, "steamdeck"),
                await(pcgamingwiki, deadline, "pcgamingwiki"),
                await(trailer, deadline, "trailer"),
                appId.getNow(null));
    }

    /**
     * {@link #enrich} started now on the assembler's own executor, so the caller can build the rest of its answer
     * meanwhile; the future completes within about the aggregate timeout, never exceptionally.
     */
    public CompletableFuture<GameEnrichment> enrichAsync(DetectedGame detected, Optional<String> igdbId,
                                                         Optional<String> steamAppId, String name, MoreLinks links) {
        return CompletableFuture.supplyAsync(() -> enrich(detected, igdbId, steamAppId, name, links), executor)
                .exceptionally(e -> {
                    log.warn("Game enrichment failed: {}", e.getMessage(), e);
                    return GameEnrichment.EMPTY;
                });
    }

    @PreDestroy
    void shutdown() {
        // shutdown(), not shutdownNow(): a late source still finishing must not be interrupted
        executor.shutdown();
    }

    /** By Steam app id when there is one, else by name. */
    private SteamGridDbAssets steamGridDb(String steamAppId, String name) {
        if (steamAppId != null) {
            return steamGridDbService.bySteamAppId(steamAppId, FetchBudget.UNLIMITED).orElse(null);
        }
        return hasText(name) ? steamGridDbService.byName(name, FetchBudget.UNLIMITED).orElse(null) : null;
    }

    /** By Steam app id when there is one, else by name. */
    private PcGamingWikiPage pcGamingWiki(String steamAppId, String name) {
        if (steamAppId != null) {
            return pcGamingWikiService.bySteamAppId(steamAppId, FetchBudget.UNLIMITED).orElse(null);
        }
        return hasText(name) ? pcGamingWikiService.byName(name, FetchBudget.UNLIMITED).orElse(null) : null;
    }

    /**
     * The MP4 route when {@link TrailerSourceResolver} finds an HLS H.264 stream the route can rebuild (looked up
     * in the same order as the route does: by Steam app id, then by Microsoft Store product), else IGDB's first
     * video on YouTube; null when there is neither.
     */
    private GameEnrichment.Trailer trailer(DetectedGame detected, String steamAppId, String igdbId, MoreLinks links) {
        String xboxProductId = detected.getSourceType() == GameBinding.SourceType.XBOX
                ? detected.getSourceId() : null;
        if (trailerSourceResolver.resolve(steamAppId, xboxProductId, FetchBudget.UNLIMITED).isPresent()) {
            return new GameEnrichment.Trailer(GameEnrichment.Trailer.MP4,
                    links.baseUrl() + CURRENT_GAME_TRAILER_PATH + MoreLinks.SINCE_V3_QUERY);
        }
        if (igdbId == null) {
            return null;
        }
        return igdbGameDetailsService.getDetails(igdbId)
                .map(IgdbGameDetails::getVideoIds)
                .filter(ids -> ids != null && !ids.isEmpty())
                .map(ids -> new GameEnrichment.Trailer(GameEnrichment.Trailer.YOUTUBE,
                        ExternalLinks.youtubeVideo(ids.getFirst())))
                .orElse(null);
    }

    /** {@code task} on the executor; null when it throws. */
    private <T> CompletableFuture<T> supply(String source, Supplier<T> task) {
        return CompletableFuture.supplyAsync(() -> guarded(source, task), executor);
    }

    /** {@code task} on the executor once {@code appId} is known; null when it throws. */
    private <T> CompletableFuture<T> after(CompletableFuture<String> appId, String source, Function<String, T> task) {
        return appId.thenApplyAsync(id -> guarded(source, () -> task.apply(id)), executor);
    }

    private static <T> T guarded(String source, Supplier<T> task) {
        try {
            return task.get();
        } catch (UpstreamBudgetExceededException e) {
            log.debug("Enrichment source {} skipped: upstream budget spent", source);
            return null;
        } catch (RuntimeException e) {
            log.warn("Enrichment source {} failed: {}", source, e.getMessage(), e);
            return null;
        }
    }

    /** The answer of {@code future} if it comes before {@code deadline}; null otherwise, leaving it running. */
    private static <T> T await(CompletableFuture<T> future, long deadline, String source) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.debug("Enrichment source {} too slow, left to finish in the background", source);
            return null;
        } catch (ExecutionException e) {
            log.warn("Enrichment source {} failed: {}", source, e.getMessage(), e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
