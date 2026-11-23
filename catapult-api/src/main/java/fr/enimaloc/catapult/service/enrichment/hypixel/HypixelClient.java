package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelGames;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelPlayer;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.HypixelSession;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelModels.MinecraftProfile;

/**
 * The Hypixel API (with a key of the shared pool for the player routes, none for the games list) and Mojang's
 * public profile lookup (no key). Every request is charged to the {@link HypixelRateLimiter}. Failures are
 * {@link HypixelUnavailableException}; only the recognised answers are {@link HypixelNotFoundException}.
 */
public interface HypixelClient {

    /** The Minecraft profile of a name (Mojang). */
    MinecraftProfile profileByName(String name);

    /** The player of a Minecraft UUID (32 hex digits); not found when they never joined Hypixel. */
    HypixelPlayer player(String uuid);

    /** Where the player is now. */
    HypixelSession status(String uuid);

    /** The Hypixel games and their clean names (no key). */
    HypixelGames games();
}
