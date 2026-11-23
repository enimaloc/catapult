package fr.enimaloc.catapult.service.enrichment.hypixel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The shapes read from the Hypixel API and Mojang's profile lookup (the projections that are cached). */
public final class HypixelModels {

    private HypixelModels() {
    }

    /** A Minecraft profile (Mojang): its UUID (32 hex digits, no dashes) and name as Mojang spells it. */
    public record MinecraftProfile(String uuid, String name) {
    }

    /**
     * The projection of a Hypixel player ({@code /v2/player}): what the stats show, nothing else of the (large)
     * answer. {@code lastLogin} is null when the player hides their online status in the API settings
     * ({@code onlineStatusHidden}).
     */
    public record HypixelPlayer(String uuid, String displayName, String rank, long networkExp, Integer karma,
                                Integer achievementPoints, boolean onlineStatusHidden, Instant lastLogin,
                                Instant lastLogout, BedWarsRaw bedWars, SkyWarsRaw skyWars) {
    }

    /** Bed Wars, as Hypixel counts it; null fields where the player has none. */
    public record BedWarsRaw(Integer stars, Integer wins, Integer losses, Integer finalKills, Integer finalDeaths,
                             Integer bedsBroken) {
    }

    /** SkyWars, as Hypixel counts it; null fields where the player has none. */
    public record SkyWarsRaw(Integer stars, Integer wins, Integer losses, Integer kills, Integer deaths) {
    }

    /** The player's session now ({@code /v2/status}): online or not, and where. */
    public record HypixelSession(boolean online, String gameType, String mode, String map) {
    }

    /** One Hypixel game ({@code /v2/resources/games}): its type key, clean name, and whether it is still played. */
    public record HypixelGameInfo(String type, String name, boolean retired, boolean legacy) {
    }

    /** The Hypixel games, by type key ({@code BEDWARS}…), in Hypixel's order. */
    public record HypixelGames(List<HypixelGameInfo> games) {
        public HypixelGames {
            games = List.copyOf(games);
        }

        /** The clean names by type key. */
        public Map<String, String> names() {
            java.util.LinkedHashMap<String, String> names = new java.util.LinkedHashMap<>();
            for (HypixelGameInfo game : games) {
                names.put(game.type(), game.name());
            }
            return names;
        }
    }
}
