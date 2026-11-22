package fr.enimaloc.catapult.service.enrichment.steamstats;

import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;

import java.time.Instant;
import java.util.Map;

/**
 * The achievements of a member on one app: {@code status} is {@code OK}, {@code PRIVATE} (profile or game details
 * not public) or {@code NO_ACHIEVEMENTS} (the app has no stats); {@code unlocked} maps an achievement's API name to
 * when it was unlocked (null when Steam gives no date), and is empty unless OK.
 */
public record SteamPlayerAchievements(Status status, Map<String, Instant> unlocked) {

    public static SteamPlayerAchievements of(Status status) {
        return new SteamPlayerAchievements(status, Map.of());
    }
}
