package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelClient;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.BedWarsRaw;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGameInfo;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGames;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelSession;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.MinecraftProfile;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.SkyWarsRaw;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Hypixel and Mojang in the mock profile: every Minecraft name exists (its UUID is derived from it) except
 * {@code ghost}, which Mojang does not know, and {@code stranger}, who never joined Hypixel; each player is network
 * level 87, 4★ Bed Wars, 12★ SkyWars and playing Bed Wars now, except {@code hidden}, who hides their online status.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedHypixelClient implements HypixelClient {

    static final String STRANGER = uuidOf("stranger");
    static final String HIDDEN = uuidOf("hidden");

    /** The UUID the simulation gives a name (32 hex digits). */
    public static String uuidOf(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("hypixel:" + name.toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
        return uuid.toString().replace("-", "");
    }

    @Override
    public MinecraftProfile profileByName(String name) {
        if (name.equalsIgnoreCase("ghost")) {
            throw new HypixelNotFoundException("simulated: no such Minecraft name");
        }
        return new MinecraftProfile(uuidOf(name), name);
    }

    @Override
    public HypixelPlayer player(String uuid) {
        if (uuid.equals(STRANGER)) {
            throw new HypixelNotFoundException("simulated: never joined Hypixel");
        }
        boolean hidden = uuid.equals(HIDDEN);
        Instant login = hidden ? null : Instant.parse("2026-11-23T18:00:00Z");
        return new HypixelPlayer(uuid, hidden ? "hidden" : "SimPlayer",
                "MVP_PLUS", 10_000_000L, 1_234_567, 5_420, hidden, login,
                hidden ? null : Instant.parse("2026-11-23T17:00:00Z"),
                new BedWarsRaw(4, 120, 98, 310, 102, 145), new SkyWarsRaw(12, 64, 210, 380, 260));
    }

    @Override
    public HypixelSession status(String uuid) {
        return new HypixelSession(true, "BEDWARS", "BEDWARS_EIGHT_TWO", "Lighthouse");
    }

    @Override
    public HypixelGames games() {
        return new HypixelGames(List.of(new HypixelGameInfo("BEDWARS", "Bed Wars", false, false),
                new HypixelGameInfo("SKYWARS", "SkyWars", false, false),
                new HypixelGameInfo("SKYBLOCK", "SkyBlock", false, false),
                new HypixelGameInfo("QUAKECRAFT", "Quakecraft", false, true)));
    }
}
