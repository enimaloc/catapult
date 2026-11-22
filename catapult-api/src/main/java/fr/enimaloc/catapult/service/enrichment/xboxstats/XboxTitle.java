package fr.enimaloc.catapult.service.enrichment.xboxstats;

import java.time.Instant;
import java.util.List;

/**
 * One entry of the member's Titlehub history, with the achievement summary Titlehub gives (null counters when it
 * gave none). {@code productIds} are the Store ids it lists (AvailabilityId / ProductId).
 */
public record XboxTitle(String titleId, String name, List<String> productIds, String type,
                        Integer currentAchievements, Integer totalAchievements, Integer currentGamerscore,
                        Integer totalGamerscore, Instant lastTimePlayed) {
}
