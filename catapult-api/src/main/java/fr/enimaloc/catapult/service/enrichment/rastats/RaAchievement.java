package fr.enimaloc.catapult.service.enrichment.rastats;

import java.time.Instant;

/**
 * An achievement, of a game's progress ({@code gameId} / {@code gameTitle} null there) or of the recent ones
 * ({@code displayOrder}, {@code numAwarded*} null there). {@code dateEarned} null means still locked;
 * {@code dateEarnedHardcore} is set only for a hardcore unlock.
 */
public record RaAchievement(int id, String title, String description, int points, int trueRatio, String badgeUrl,
                            Integer displayOrder, String type, Integer numAwarded, Integer numAwardedHardcore,
                            Instant dateEarned, Instant dateEarnedHardcore, Integer gameId, String gameTitle) {
}
