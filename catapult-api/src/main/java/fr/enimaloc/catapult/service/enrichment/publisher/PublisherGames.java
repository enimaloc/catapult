package fr.enimaloc.catapult.service.enrichment.publisher;

import com.fasterxml.jackson.annotation.JsonProperty;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.getter.DetectedGame;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Tells which publisher game a detected game is. */
public final class PublisherGames {

    private PublisherGames() {
    }

    /** The Blizzard games the enrichment knows. */
    public enum BlizzardGame {
        @JsonProperty("wow") WOW,
        @JsonProperty("sc2") SC2,
        @JsonProperty("d3") D3,
        @JsonProperty("hearthstone") HEARTHSTONE
    }

    /**
     * The Blizzard game of a detected one: by Battle.net product code when detected there, else by IGDB id. A
     * variant ({@code WoW:Forever}) is not the retail game: empty (see {@link #isBlizzardVariant}).
     */
    public static Optional<BlizzardGame> blizzard(DetectedGame game, String igdbId) {
        if (game != null && game.getSourceType() == SourceType.BATTLENET) {
            String id = game.getSourceId();
            if (id == null || id.contains(":")) {
                return Optional.empty();
            }
            return Optional.ofNullable(switch (id) {
                case "WoW" -> BlizzardGame.WOW;
                case "S2" -> BlizzardGame.SC2;
                case "D3" -> BlizzardGame.D3;
                case "WTCG" -> BlizzardGame.HEARTHSTONE;
                default -> null;
            });
        }
        if (igdbId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (igdbId) {
            case "123" -> BlizzardGame.WOW;
            case "239" -> BlizzardGame.SC2;
            case "120" -> BlizzardGame.D3;
            case "1279" -> BlizzardGame.HEARTHSTONE;
            default -> null;
        });
    }

    /** The Bungie games the enrichment knows. */
    public enum BungieGame {
        @JsonProperty("destiny2") DESTINY2
    }

    /** IGDB's id of Destiny 2 (checked on IGDB on 2026-11-23: {@code destiny-2}; the expansions have ids of their own). */
    static final String DESTINY2_IGDB_ID = "25657";

    /** The Bungie game of a detected one, by IGDB id (Bungie has no detection source of its own). */
    public static Optional<BungieGame> bungie(String igdbId) {
        return DESTINY2_IGDB_ID.equals(igdbId) ? Optional.of(BungieGame.DESTINY2) : Optional.empty();
    }

    /** The FACEIT games the enrichment knows. */
    public enum FaceitGame {
        @JsonProperty("cs2") CS2;

        /** FACEIT's own id of the game. */
        public String faceitId() {
            return "cs2";
        }
    }

    /** Counter-Strike 2's Steam app id. */
    static final String CS2_STEAM_APP_ID = "730";

    /**
     * The FACEIT game of a detected one: by Steam app id when detected on Steam, else by IGDB id ({@code cs2IgdbId}
     * is Counter-Strike 2's; an empty one maps nothing).
     */
    public static Optional<FaceitGame> faceit(DetectedGame game, String igdbId, String cs2IgdbId) {
        if (game != null && game.getSourceType() == SourceType.STEAM && CS2_STEAM_APP_ID.equals(game.getSourceId())) {
            return Optional.of(FaceitGame.CS2);
        }
        if (igdbId != null && !igdbId.isBlank() && igdbId.equals(cs2IgdbId)) {
            return Optional.of(FaceitGame.CS2);
        }
        return Optional.empty();
    }

    /** A Battle.net variant of a game (the source id carries a {@code :}, like {@code WoW:Forever}). */
    public static boolean isBlizzardVariant(DetectedGame game) {
        return game != null && game.getSourceType() == SourceType.BATTLENET && game.getSourceId() != null
                && game.getSourceId().contains(":");
    }

    /** The Riot games the enrichment knows. */
    public enum RiotGame {
        @JsonProperty("lol") LOL,
        @JsonProperty("tft") TFT,
        @JsonProperty("valorant") VALORANT
    }

    /**
     * The Riot game of a detected one, by its IGDB id (no Riot client is detected): {@code ids} gives the ids of
     * League of Legends, Teamfight Tactics and Valorant (an empty one maps no game).
     */
    public static Optional<RiotGame> riot(String igdbId, String lolId, String tftId, String valorantId) {
        if (igdbId == null || igdbId.isBlank()) {
            return Optional.empty();
        }
        if (igdbId.equals(lolId)) {
            return Optional.of(RiotGame.LOL);
        }
        if (igdbId.equals(tftId)) {
            return Optional.of(RiotGame.TFT);
        }
        if (igdbId.equals(valorantId)) {
            return Optional.of(RiotGame.VALORANT);
        }
        return Optional.empty();
    }

    /** The IGDB ids of the chess sites, checked on IGDB on 2026-11-23: Chess.com, Lichess. */
    private static final Set<String> CHESS_IGDB_IDS = Set.of("121957", "122008");
    /**
     * The detected names that are chess: the Twitch category "Chess" (it has no IGDB id) and the two sites' names.
     */
    private static final Set<String> CHESS_NAMES = Set.of("chess", "chess.com", "lichess", "lichess.org");

    /** Whether a detected game is chess: by the sites' IGDB ids, else by the detected name. */
    public static boolean chess(DetectedGame game, String igdbId) {
        if (igdbId != null && CHESS_IGDB_IDS.contains(igdbId)) {
            return true;
        }
        return game != null && game.getSourceName() != null
                && CHESS_NAMES.contains(game.getSourceName().strip().toLowerCase(Locale.ROOT));
    }
}
