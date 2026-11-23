package fr.enimaloc.catapult.service.enrichment.hypixel;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.HypixelGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A member's personal stats on Hypixel, the one projection of the aggregate and of the detail route. {@code status}
 * is that of the player read ({@code ok}; {@code not_found}: the declared Minecraft account never joined Hypixel;
 * {@code unavailable}); the objects are null unless it is {@code ok} ({@code bedwars} / {@code skywars} also null
 * for a player who never played them). {@code account} is the Minecraft name. The {@code online} status has its own
 * status: {@code private} when the player hides it in their Hypixel API settings. The Bed Wars and SkyWars details
 * (losses, kills…) are filled in the detail only (absent from the aggregate). {@code more} links to the detail
 * route; the service leaves it null, the API sets it ({@link #withMore}).
 */
@Schema(name = "HypixelStats")
public record HypixelStats(Status status, HypixelGame game, String account, HypixelNetworkStats network,
                           HypixelBedWarsStats bedwars, HypixelSkyWarsStats skywars, HypixelOnlineStatus online,
                           String more) {

    /** These stats with the link to their detail route. */
    public HypixelStats withMore(String link) {
        return new HypixelStats(status, game, account, network, bedwars, skywars, online, link);
    }

    /**
     * The Hypixel network: level (from the network experience), karma, achievement points, rank ({@code MVP_PLUS}…,
     * null for none), and the last login (null when the player hides their online status).
     */
    @Schema(name = "HypixelNetworkStats")
    public record HypixelNetworkStats(int level, long experience, Integer karma, Integer achievementPoints,
                                      String rank, Instant lastLogin) {
    }

    /** Bed Wars: stars and wins; losses, final kills / deaths and beds broken in the detail only. */
    @Schema(name = "HypixelBedWarsStats")
    public record HypixelBedWarsStats(Integer stars, Integer wins,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer losses,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer finalKills,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer finalDeaths,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer bedsBroken) {
    }

    /** SkyWars: stars and wins; losses, kills and deaths in the detail only. */
    @Schema(name = "HypixelSkyWarsStats")
    public record HypixelSkyWarsStats(Integer stars, Integer wins,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer losses,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer kills,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Integer deaths) {
    }

    /**
     * Where the player is now: {@code status} {@code ok} (then {@code online}, and when online the game type
     * {@code BEDWARS}…, its clean name, the mode and the map), {@code private} (the player hides it: nothing else),
     * {@code unavailable}.
     */
    @Schema(name = "HypixelOnlineStatus")
    public record HypixelOnlineStatus(Status status, Boolean online, String gameType, String gameName, String mode,
                                      String map) {
    }
}
