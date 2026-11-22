package fr.enimaloc.catapult.service.enrichment.publisher;

import com.fasterxml.jackson.annotation.JsonProperty;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.getter.DetectedGame;

import java.util.Optional;

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

    /** A Battle.net variant of a game (the source id carries a {@code :}, like {@code WoW:Forever}). */
    public static boolean isBlizzardVariant(DetectedGame game) {
        return game != null && game.getSourceType() == SourceType.BATTLENET && game.getSourceId() != null
                && game.getSourceId().contains(":");
    }
}
