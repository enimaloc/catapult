package fr.enimaloc.catapult.api.userapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.hltb.TimeToBeat;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;

import java.util.Locale;
import java.util.Optional;

/**
 * The v3 aggregated game info: the v2 platform objects ({@link GameInfoResponse}, kept as {@code base}) plus the
 * enrichment ones (HowLongToBeat, ProtonDB, SteamGridDB, Steam Deck, PCGamingWiki), each a short projection with a
 * "more" link to its detail route, null when unavailable. The {@link GameInfoResponse.Summary} view adds to the v2
 * derived fields {@code timeToBeat}, {@code protonTier}, {@code deckStatus}, {@code coverUrl} and {@code trailer};
 * the enrichment objects never feed {@code name} or {@code description}.
 */
public record GameInfoV3Response(@JsonView(GameInfoResponse.Summary.class) boolean inGame,
                                 GameInfoResponse.IgdbObject igdb, GameInfoResponse.SteamObject steam,
                                 GameInfoResponse.XboxObject xbox, GameInfoResponse.DtddObject dtdd,
                                 GameInfoResponse.CatapultObject catapult, HltbObject hltb, ProtonDbObject protondb,
                                 SteamGridDbObject steamgriddb, SteamDeckObject steamDeck,
                                 PcGamingWikiObject pcgamingwiki,
                                 @JsonView(GameInfoResponse.Summary.class) Trailer trailer,
                                 @JsonIgnore GameInfoResponse base) {

    /** The answer while nothing is detected: inGame=false, every object null. */
    public static GameInfoV3Response notInGame() {
        return new GameInfoV3Response(false, null, null, null, null, null, null, null, null, null, null, null,
                new GameInfoResponse(false));
    }

    /**
     * The v2 answer {@code base} completed with {@code enrichment}, the "more" links built with {@code links}. HLTB
     * only links to its detail route when the game has an IGDB id (a game found by name alone has none).
     */
    public static GameInfoV3Response of(GameInfoResponse base, GameEnrichment enrichment, MoreLinks links,
                                        Optional<String> igdbId) {
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

    // ---------------------------------------------------------------- objects

    /** HowLongToBeat durations in seconds (null when unknown) and where they came from. */
    public record HltbObject(Long hastily, Long normally, Long completely, int count, TimeToBeat.Origin origin,
                             String url, String more) implements GameInfoResponse.HasMore {
        static HltbObject of(TimeToBeat ttb, String more) {
            return ttb == null ? null : new HltbObject(ttb.hastily(), ttb.normally(), ttb.completely(), ttb.count(),
                    ttb.origin(), ttb.hltbUrl(), more);
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
                    c.category() == null ? null : c.category().name().toLowerCase(Locale.ROOT), more);
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
