package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticGame;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.prices.GamePrices;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunEnrichment;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunIdentity;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRecords;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRun;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivity;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchClips;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchLives;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The v3 aggregated game info: the v2 platform objects ({@link GameInfoResponse}, kept as {@code base}) plus the
 * enrichment ones (HowLongToBeat, ProtonDB, SteamGridDB, Steam Deck, PCGamingWiki, store prices, OpenCritic, Twitch
 * activity, speedrun.com), each a short projection with a
 * "more" link to its detail route, null when unavailable. The {@link GameInfoResponse.Summary} view adds to the v2
 * derived fields {@code timeToBeat}, {@code protonTier}, {@code deckStatus}, {@code coverUrl}, {@code trailer}, {@code price},
 * {@code criticScore}, {@code liveViewers}, {@code worldRecord} and {@code personalBest};
 * the enrichment objects never feed {@code name} or {@code description}.
 */
public record GameInfoV3Response(@JsonView(GameInfoResponse.Summary.class) boolean inGame,
                                 GameInfoResponse.IgdbObject igdb, GameInfoResponse.SteamObject steam,
                                 GameInfoResponse.XboxObject xbox, GameInfoResponse.DtddObject dtdd,
                                 GameInfoResponse.CatapultObject catapult, HltbObject hltb, ProtonDbObject protondb,
                                 SteamGridDbObject steamgriddb, SteamDeckObject steamDeck,
                                 PcGamingWikiObject pcgamingwiki, PricesObject prices,
                                 OpenCriticObject opencritic, TwitchObject twitch, SpeedrunObject speedrun,
                                 @JsonView(GameInfoResponse.Summary.class) Trailer trailer,
                                 @JsonIgnore GameInfoResponse base) {

    /** The answer while nothing is detected: inGame=false, every object null. */
    public static GameInfoV3Response notInGame() {
        return new GameInfoV3Response(false, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, new GameInfoResponse(false));
    }

    /**
     * The v2 answer {@code base} completed with {@code enrichment}, the "more" links built with {@code links}. HLTB
     * only links to its detail route when the game has an IGDB id (a game found by name alone has none).
     */
    public static GameInfoV3Response of(GameInfoResponse base, GameEnrichment enrichment, MoreLinks links,
                                        Optional<String> igdbId) {
        return of(base, enrichment, links, igdbId, null, null);
    }

    /**
     * Like {@link #of(GameInfoResponse, GameEnrichment, MoreLinks, Optional)}; {@code country} and
     * {@code language} are the request's query parameters, null when it gave none, carried over to the "more"
     * links of the prices and of the Twitch activity so that the detail route answers the same question.
     */
    public static GameInfoV3Response of(GameInfoResponse base, GameEnrichment enrichment, MoreLinks links,
                                        Optional<String> igdbId, String country, String language) {
        String appId = enrichment.steamAppId();
        return new GameInfoV3Response(base.inGame(), base.igdb(), base.steam(), base.xbox(), base.dtdd(),
                base.catapult(),
                HltbObject.of(enrichment.hltb(), igdbId.map(id -> links.to(MoreLinks.Kind.HLTB, id)).orElse(null)),
                ProtonDbObject.of(enrichment.protondb(),
                        appId == null ? null : links.to(MoreLinks.Kind.PROTONDB, appId)),
                SteamGridDbObject.of(enrichment.steamgriddb(), links),
                SteamDeckObject.of(enrichment.steamDeck(),
                        appId == null ? null : links.to(MoreLinks.Kind.STEAMDECK, appId)),
                PcGamingWikiObject.of(enrichment.pcgamingwiki(), links),
                PricesObject.of(enrichment.prices(), links, country),
                OpenCriticObject.of(enrichment.opencritic(), links),
                TwitchObject.of(enrichment.twitch(), links, language),
                SpeedrunObject.of(enrichment.speedrun(), links),
                Trailer.of(enrichment.trailer()),
                base);
    }

    // ---------------------------------------------------------------- summary, as in v2

    @JsonView(GameInfoResponse.Summary.class)
    public String name() {
        return base.name();
    }

    @JsonView(GameInfoResponse.Summary.class)
    public String description() {
        return base.description();
    }

    @JsonView(GameInfoResponse.Summary.class)
    public String tws() {
        return base.tws();
    }

    @JsonView(GameInfoResponse.Summary.class)
    public Object releaseDate() {
        return base.releaseDate();
    }

    @JsonView(GameInfoResponse.Summary.class)
    public String igdbUrl() {
        return base.igdbUrl();
    }

    @JsonView(GameInfoResponse.Summary.class)
    public String storeUrl() {
        return base.storeUrl();
    }

    @JsonView(GameInfoResponse.Summary.class)
    public GameInfoResponse.IaDisclosure ia() {
        return base.ia();
    }

    // ---------------------------------------------------------------- summary, new in v3

    /** How long the main story plus some extras takes, in seconds (HowLongToBeat's "normally"); null if unknown. */
    @JsonView(GameInfoResponse.Summary.class)
    public Long timeToBeat() {
        return hltb == null ? null : hltb.normally();
    }

    /** ProtonDB's tier (platinum, gold...); null if unknown. */
    @JsonView(GameInfoResponse.Summary.class)
    public String protonTier() {
        return protondb == null ? null : protondb.tier();
    }

    /** The Steam Deck verdict (verified, playable, unsupported, unknown); null if unknown. */
    @JsonView(GameInfoResponse.Summary.class)
    public String deckStatus() {
        return steamDeck == null ? null : steamDeck.category();
    }

    /** SteamGridDB's best grid, else IGDB's cover; null when neither exists. */
    @JsonView(GameInfoResponse.Summary.class)
    public String coverUrl() {
        if (steamgriddb != null && steamgriddb.grid() != null) {
            return steamgriddb.grid();
        }
        return igdb == null ? null : igdb.coverUrl();
    }

    /** The best current official-store price, null when none is known. */
    @JsonView(GameInfoResponse.Summary.class)
    public Price price() {
        return prices == null || prices.best() == null ? null : Price.of(prices.currency(), prices.best());
    }

    /** The top critics' average score on OpenCritic (0 to 100); null if unknown. */
    @JsonView(GameInfoResponse.Summary.class)
    public Integer criticScore() {
        return opencritic == null ? null : opencritic.topCriticScore();
    }

    /** How many people watch the game on Twitch right now; null if unknown. */
    @JsonView(GameInfoResponse.Summary.class)
    public Long liveViewers() {
        return twitch == null ? null : twitch.viewers();
    }

    /** The world record of speedrun.com's main category of the game; null without one. */
    @JsonView(GameInfoResponse.Summary.class)
    public WorldRecord worldRecord() {
        if (speedrun == null || speedrun.worldRecords().isEmpty()) {
            return null;
        }
        SpeedrunRecords.CategoryRecord main = speedrun.worldRecords().getFirst();
        return new WorldRecord(main.category(), main.time(),
                main.runners().stream().map(SpeedrunRecords.Runner::name).toList());
    }

    /**
     * The member's personal best on the game in speedrun.com's main category, else their best place in any
     * category; null when none is known.
     */
    @JsonView(GameInfoResponse.Summary.class)
    public PersonalBest personalBest() {
        if (speedrun == null || speedrun.personalBests() == null) {
            return null;
        }
        String mainCategory = speedrun.worldRecords().isEmpty() ? null
                : speedrun.worldRecords().getFirst().categoryId();
        Comparator<SpeedrunObject.Pb> byPlace = Comparator.comparing(SpeedrunObject.Pb::place,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return speedrun.personalBests().stream()
                .filter(pb -> pb.categoryId() != null && pb.categoryId().equals(mainCategory))
                .min(byPlace)
                .or(() -> speedrun.personalBests().stream().min(byPlace))
                .map(pb -> new PersonalBest(pb.category(), pb.time(), pb.place()))
                .orElse(null);
    }

    // ---------------------------------------------------------------- objects

    /** The summary's world record: the main category, its time in seconds and every runner of a tie. */
    public record WorldRecord(@JsonView(GameInfoResponse.Summary.class) String category,
                              @JsonView(GameInfoResponse.Summary.class) BigDecimal time,
                              @JsonView(GameInfoResponse.Summary.class) List<String> runners) {
    }

    /** The summary's personal best: its category, its time in seconds and its place on the leaderboard. */
    public record PersonalBest(@JsonView(GameInfoResponse.Summary.class) String category,
                               @JsonView(GameInfoResponse.Summary.class) BigDecimal time,
                               @JsonView(GameInfoResponse.Summary.class) Integer place) {
    }

    /**
     * Speedrun.com's game, the world records of its first {@value #MAX_CATEGORIES} main full-game categories (the
     * first is the main one; the detail route has up to 20), and the member's own account, personal bests and runs
     * waiting for verification on the game. {@code account}, {@code personalBests} and {@code pendingRuns} are null
     * when no account is identified: unknown, not "none".
     */
    public record SpeedrunObject(String gameId, String name, String url, String coverUrl,
                                 List<SpeedrunRecords.CategoryRecord> worldRecords, SpeedrunAccountRef account,
                                 List<Pb> personalBests, List<Pending> pendingRuns, String more)
            implements GameInfoResponse.HasMore {
        /** How many categories the aggregate keeps. */
        static final int MAX_CATEGORIES = 3;

        static SpeedrunObject of(SpeedrunEnrichment s, MoreLinks links) {
            if (s == null || s.records() == null || s.records().game() == null) {
                return null;
            }
            SpeedrunRecords records = s.records();
            List<SpeedrunRecords.CategoryRecord> categories = records.categories() == null ? List.of()
                    : records.categories().stream().limit(MAX_CATEGORIES).toList();
            return new SpeedrunObject(records.game().id(), records.game().name(), records.game().url(),
                    records.game().coverUrl(), categories, SpeedrunAccountRef.of(s.account()),
                    s.personalBests() == null ? null : s.personalBests().stream().map(Pb::of).toList(),
                    s.pendingRuns() == null ? null : s.pendingRuns().stream().map(Pending::of).toList(),
                    links.to(MoreLinks.Kind.SPEEDRUN, records.game().id()));
        }

        /**
         * The member's speedrun.com account and how it was found ({@code api_key}, {@code twitch}, {@code declared}).
         * Named for the OpenAPI schema, which takes the simple class name: a bare {@code Account} would merge.
         */
        public record SpeedrunAccountRef(String name, String url, SpeedrunIdentity.Source source) {
            public static SpeedrunAccountRef of(SpeedrunIdentity identity) {
                return identity == null ? null
                        : new SpeedrunAccountRef(identity.name(), identity.url(), identity.source());
            }
        }

        /** A personal best on the game: its category, place, time in seconds, date and run page. */
        public record Pb(String categoryId, String category, Integer place, BigDecimal time, LocalDate date,
                         String runUrl) {
            static Pb of(SpeedrunRun run) {
                return new Pb(run.categoryId(), run.category(), run.place(), run.time(), run.date(), run.runUrl());
            }
        }

        /** A run on the game waiting for verification: its category, time in seconds, submission and run page. */
        public record Pending(String categoryId, String category, BigDecimal time, Instant submitted,
                              String runUrl) {
            static Pending of(SpeedrunRun run) {
                return new Pending(run.categoryId(), run.category(), run.time(), run.submitted(), run.runUrl());
            }
        }
    }

    /** The best price of the summary: {@code amount} in {@code currency}, {@code cut} percent off, at {@code shop}. */
    public record Price(@JsonView(GameInfoResponse.Summary.class) BigDecimal amount,
                        @JsonView(GameInfoResponse.Summary.class) String currency,
                        @JsonView(GameInfoResponse.Summary.class) int cut,
                        @JsonView(GameInfoResponse.Summary.class) String shop) {
        static Price of(String currency, GamePrices.Deal best) {
            return new Price(best.price(), currency, best.cut(), best.shop());
        }
    }

    /** Official-store prices in a country: the best deal, the all-time low and the cheapest deals. */
    public record PricesObject(String country, String currency, GamePrices.Deal best,
                               GamePrices.HistoryLow historyLow, List<GamePrices.Deal> deals, String itadUrl,
                               String more) implements GameInfoResponse.HasMore {
        /** How many deals the aggregate keeps; the detail route has them all. */
        static final int MAX_DEALS = 5;

        static PricesObject of(GamePrices p, MoreLinks links, String country) {
            if (p == null) {
                return null;
            }
            Map<String, String> query = new LinkedHashMap<>();
            if (country != null) {
                query.put("country", country);
            }
            List<GamePrices.Deal> deals = p.deals() == null ? List.of()
                    : p.deals().stream().limit(MAX_DEALS).toList();
            return new PricesObject(p.country(), p.currency(), p.best(), p.historyLow(), deals, p.itadUrl(),
                    p.itadId() == null ? null : links.to(MoreLinks.Kind.PRICES, p.itadId(), query));
        }
    }

    /** OpenCritic's score of the game; {@code numReviews} counts the top critics' reviews only. */
    public record OpenCriticObject(Integer topCriticScore, Integer percentRecommended, String tier, int numReviews,
                                   String url, String more) implements GameInfoResponse.HasMore {
        static OpenCriticObject of(OpenCriticGame g, MoreLinks links) {
            return g == null ? null : new OpenCriticObject(g.topCriticScore(), g.percentRecommended(), g.tier(),
                    g.numReviews(), g.url(), links.to(MoreLinks.Kind.OPENCRITIC, g.id()));
        }
    }

    /** What goes on in the game's Twitch category: lives, viewers, top streams and clips. */
    public record TwitchObject(String categoryId, String categoryName, int liveCount, long viewers,
                               boolean approximate, List<TwitchLives.Stream> topStreams,
                               List<TwitchClips.Clip> topClips, String more) implements GameInfoResponse.HasMore {
        static TwitchObject of(TwitchActivity a, MoreLinks links, String language) {
            if (a == null) {
                return null;
            }
            Map<String, String> query = new LinkedHashMap<>();
            if (language != null) {
                query.put("language", language);
            }
            return new TwitchObject(a.categoryId(), a.categoryName(), a.liveCount(), a.viewers(), a.approximate(),
                    a.topStreams(), a.topClips(), links.to(MoreLinks.Kind.TWITCH, a.categoryId(), query));
        }
    }

    /** HowLongToBeat durations in seconds (null when unknown), as served by IGDB. */
    public record HltbObject(Long hastily, Long normally, Long completely, int count, String more) implements GameInfoResponse.HasMore {
        static HltbObject of(TimeToBeat ttb, String more) {
            return ttb == null ? null : new HltbObject(ttb.hastily(), ttb.normally(), ttb.completely(), ttb.count(), more);
        }
    }

    /** ProtonDB's compatibility summary. */
    public record ProtonDbObject(String tier, String trendingTier, String bestReportedTier, String confidence,
                                 double score, int total, String url, String more)
            implements GameInfoResponse.HasMore {
        static ProtonDbObject of(ProtonDbSummary s, String more) {
            return s == null ? null : new ProtonDbObject(s.tier(), s.trendingTier(), s.bestReportedTier(),
                    s.confidence(), s.score(), s.total(), s.url(), more);
        }
    }

    /** SteamGridDB's best artwork of each kind (null when it has none). */
    public record SteamGridDbObject(long gameId, String name, String url, String grid, String hero, String logo,
                                    String icon, String more) implements GameInfoResponse.HasMore {
        static SteamGridDbObject of(SteamGridDbAssets a, MoreLinks links) {
            return a == null ? null : new SteamGridDbObject(a.gameId(), a.name(), a.url(), url(a.bestGrid()),
                    url(a.bestHero()), url(a.bestLogo()), url(a.bestIcon()),
                    links.to(MoreLinks.Kind.STEAMGRIDDB, a.gameId()));
        }

        private static String url(SteamGridDbAssets.Asset asset) {
            return asset == null ? null : asset.url();
        }
    }

    /** The Steam Deck verdict, in lower case; the tested items are on the detail route. */
    public record SteamDeckObject(String category, String more) implements GameInfoResponse.HasMore {
        static SteamDeckObject of(SteamDeckCompatibility c, String more) {
            return c == null ? null : new SteamDeckObject(
                    c.category() == null ? null : c.category().json(), more);
        }
    }

    /** PCGamingWiki's page and its raw support values (true, false, limited, hackable...). */
    public record PcGamingWikiObject(long pageId, String title, String url, String ultrawide, String controller,
                                     String hdr, String more) implements GameInfoResponse.HasMore {
        static PcGamingWikiObject of(PcGamingWikiPage p, MoreLinks links) {
            return p == null ? null : new PcGamingWikiObject(p.pageId(), p.title(), p.url(), p.ultrawide(),
                    p.controller(), p.hdr(), links.to(MoreLinks.Kind.PCGAMINGWIKI, p.pageId()));
        }
    }

    /** Where to watch the trailer: {@code kind} "mp4" (the API's own MP4 route) or "youtube". */
    public record Trailer(@JsonView(GameInfoResponse.Summary.class) String kind,
                          @JsonView(GameInfoResponse.Summary.class) String url) {
        static Trailer of(GameEnrichment.Trailer t) {
            return t == null ? null : new Trailer(t.kind(), t.url());
        }
    }
}
