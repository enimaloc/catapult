package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.api.userapi.MoreLinks;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.igdb.IgdbGameDetails;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.enrichment.hltb.HltbService;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticGame;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticService;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiService;
import fr.enimaloc.catapult.service.enrichment.prices.GamePrices;
import fr.enimaloc.catapult.service.enrichment.prices.PricesService;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunEnrichment;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunGame;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunGameService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentityResolver;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunPersonalService;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRecords;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRun;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckService;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbService;
import fr.enimaloc.catapult.service.enrichment.trailer.TrailerSourceResolver;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivity;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivityService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.link.ExternalLinks;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
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
 * Asks the enrichment sources (HowLongToBeat, ProtonDB, SteamGridDB, Steam Deck, PCGamingWiki, store prices,
 * OpenCritic, Twitch activity, speedrun.com) and picks the
 * trailer link of a detected game, all at once on virtual threads, and waits for them at most
 * {@code catapult.enrichment.aggregate-timeout}. A source that fails is null; one that is too late is null too but
 * keeps running in the background (never interrupted), so its answer lands in the cache for the next call.
 *
 * <p>The Steam-keyed sources use the detected Steam app id, else IGDB's Steam mapping of the game; without one,
 * ProtonDB and Steam Deck are skipped and SteamGridDB and PCGamingWiki search by name. OpenCritic is searched by
 * the IGDB name when it is cached in memory, else by the detected name.
 *
 * <p>Speedrun.com is searched by that same name (with or without an IGDB id). Once the game is found, its world
 * records and the member's account are fetched side by side, then the member's personal bests and runs waiting for
 * verification on that game; all of it within the same deadline. Without records there is no speedrun.com object;
 * without an identified account (no member, none found, or too late) the personal parts are null.
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
    private final PricesService pricesService;
    private final OpenCriticService openCriticService;
    private final TwitchActivityService twitchActivityService;
    private final SpeedrunGameService speedrunGameService;
    private final SpeedrunIdentityResolver speedrunIdentityResolver;
    private final SpeedrunPersonalService speedrunPersonalService;
    private final IgdbService igdbService;
    private final IgdbGameDetailsService igdbGameDetailsService;
    private final TrailerSourceResolver trailerSourceResolver;
    private final EnrichmentProperties properties;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Everything the sources know of {@code detected}, within the aggregate timeout. {@code steamAppId} is the
     * detected Steam app id (empty for another store: IGDB's mapping is looked up from {@code igdbId}), {@code name}
     * the name to search by when there is no id, {@code country} the ISO country the prices are asked for,
     * {@code language} the language of the Twitch streams (null for any), {@code links} the origin the trailer link
     * points at, {@code member} the member whose speedrun.com account is looked up (empty: world records only).
     */
    public GameEnrichment enrich(DetectedGame detected, Optional<String> igdbId, Optional<String> steamAppId,
                                 String name, String country, String language, MoreLinks links,
                                 Optional<UserAccount> member) {
        long deadline = System.nanoTime() + properties.aggregateTimeout().toNanos();
        String igdb = igdbId.filter(id -> !id.isBlank()).orElse(null);

        CompletableFuture<String> appId = steamAppId.filter(id -> !id.isBlank())
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> supply("steam app id", () -> igdb == null ? null
                        : igdbService.findExternalUid(igdb, GameBinding.SourceType.STEAM).orElse(null)));

        var hltb = supply("hltb", () -> hltbService.timeToBeat(igdb, FetchBudget.UNLIMITED).orElse(null));
        var protondb = then(appId, "protondb", id -> id == null ? null
                : protonDbService.summary(id, FetchBudget.UNLIMITED).orElse(null));
        var steamDeck = then(appId, "steamdeck", id -> id == null ? null
                : steamDeckService.compatibility(id, FetchBudget.UNLIMITED).orElse(null));
        var steamgriddb = then(appId, "steamgriddb", id -> steamGridDb(id, name));
        var pcgamingwiki = then(appId, "pcgamingwiki", id -> pcGamingWiki(id, name));
        var trailer = then(appId, "trailer", id -> trailer(detected, id, igdb, links));
        var prices = then(appId, "prices", id -> prices(id, name, country));
        var opencritic = supply("opencritic", () -> openCritic(igdb, name));
        var twitch = supply("twitch", () -> igdb == null ? null
                : twitchActivityService.byIgdbId(igdb, language, FetchBudget.UNLIMITED).orElse(null));
        Speedrun speedrun = speedrun(searchName(igdb, name), member);

        return new GameEnrichment(
                await(hltb, deadline, "hltb"),
                await(protondb, deadline, "protondb"),
                await(steamgriddb, deadline, "steamgriddb"),
                await(steamDeck, deadline, "steamdeck"),
                await(pcgamingwiki, deadline, "pcgamingwiki"),
                await(trailer, deadline, "trailer"),
                appId.getNow(null),
                await(prices, deadline, "prices"),
                await(opencritic, deadline, "opencritic"),
                await(twitch, deadline, "twitch"),
                speedrun.await(deadline));
    }

    /**
     * {@link #enrich} started now on the assembler's own executor, so the caller can build the rest of its answer
     * meanwhile; the future completes within about the aggregate timeout, never exceptionally.
     */
    public CompletableFuture<GameEnrichment> enrichAsync(DetectedGame detected, Optional<String> igdbId,
                                                         Optional<String> steamAppId, String name, String country,
                                                         String language, MoreLinks links,
                                                         Optional<UserAccount> member) {
        return CompletableFuture.supplyAsync(
                        () -> enrich(detected, igdbId, steamAppId, name, country, language, links, member), executor)
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
    private GamePrices prices(String steamAppId, String name, String country) {
        if (steamAppId != null) {
            return pricesService.bySteamAppId(steamAppId, country, FetchBudget.UNLIMITED).orElse(null);
        }
        return hasText(name) ? pricesService.byName(name, country, FetchBudget.UNLIMITED).orElse(null) : null;
    }

    /**
     * By the IGDB name of the game when IGDB's in-memory cache already knows it (no upstream call), else by
     * {@code name}; nothing without an IGDB id.
     */
    private OpenCriticGame openCritic(String igdbId, String name) {
        if (igdbId == null) {
            return null;
        }
        String searched = searchName(igdbId, name);
        return hasText(searched) ? openCriticService.byName(searched, FetchBudget.UNLIMITED).orElse(null) : null;
    }

    /** The IGDB name of the game when IGDB's in-memory cache knows it (no upstream call), else {@code name}. */
    private String searchName(String igdbId, String name) {
        String igdbName = igdbId == null ? null : igdbService.getGameCache().get(igdbId);
        return hasText(igdbName) ? igdbName : name;
    }

    /**
     * Starts the speedrun.com lookups: the game by {@code searched}, then its records and the member's account side
     * by side, then the account's personal bests and pending runs on that game.
     */
    private Speedrun speedrun(String searched, Optional<UserAccount> member) {
        CompletableFuture<SpeedrunGame> game = supply("speedrun game", () -> hasText(searched)
                ? speedrunGameService.byName(searched, FetchBudget.UNLIMITED).orElse(null) : null);
        CompletableFuture<SpeedrunRecords> records = then(game, "speedrun records", g -> g == null ? null
                : speedrunGameService.records(g.id(), FetchBudget.UNLIMITED).orElse(null));
        CompletableFuture<SpeedrunIdentity> account = then(game, "speedrun account", g -> g == null
                ? null
                : member.flatMap(user -> speedrunIdentityResolver.resolve(user, FetchBudget.UNLIMITED)).orElse(null));
        CompletableFuture<List<SpeedrunRun>> personalBests = then(account, "speedrun personal bests", a -> a == null
                ? null
                : speedrunPersonalService.personalBests(a.userId(), game.join().id(), FetchBudget.UNLIMITED)
                        .orElse(null));
        CompletableFuture<List<SpeedrunRun>> pendingRuns = then(account, "speedrun pending runs", a -> a == null
                ? null
                : speedrunPersonalService.pendingRuns(a, game.join().id(),
                        speedrunPersonalService.apiKey(member.orElseThrow(), a), FetchBudget.UNLIMITED).orElse(null));
        return new Speedrun(records, account, personalBests, pendingRuns);
    }

    /** The speedrun.com lookups under way. */
    private record Speedrun(CompletableFuture<SpeedrunRecords> records, CompletableFuture<SpeedrunIdentity> account,
                            CompletableFuture<List<SpeedrunRun>> personalBests,
                            CompletableFuture<List<SpeedrunRun>> pendingRuns) {

        /** What arrived before {@code deadline}; null without records; the personal parts null without account. */
        SpeedrunEnrichment await(long deadline) {
            SpeedrunRecords found = GameEnrichmentAssembler.await(records, deadline, "speedrun");
            if (found == null) {
                return null;
            }
            SpeedrunIdentity identity = GameEnrichmentAssembler.await(account, deadline, "speedrun account");
            if (identity == null) {
                return new SpeedrunEnrichment(found, null, null, null);
            }
            return new SpeedrunEnrichment(found, identity,
                    GameEnrichmentAssembler.await(personalBests, deadline, "speedrun personal bests"),
                    GameEnrichmentAssembler.await(pendingRuns, deadline, "speedrun pending runs"));
        }
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

    /** {@code task} on the executor once {@code previous} is known; null when it throws. */
    private <P, T> CompletableFuture<T> then(CompletableFuture<P> previous, String source, Function<P, T> task) {
        return previous.thenApplyAsync(value -> guarded(source, () -> task.apply(value)), executor);
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
