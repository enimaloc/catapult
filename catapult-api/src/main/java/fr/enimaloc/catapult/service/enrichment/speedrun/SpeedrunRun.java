package fr.enimaloc.catapult.service.enrichment.speedrun;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A full-game run of a speedrun.com account. {@code time} is the category's reference time in seconds;
 * {@code place} is null for a run still waiting for verification.
 */
public record SpeedrunRun(String gameId, String game, String categoryId, String category, Integer place,
                          BigDecimal time, LocalDate date, Instant submitted, String runUrl) {
}
