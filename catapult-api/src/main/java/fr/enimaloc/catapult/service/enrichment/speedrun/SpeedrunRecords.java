package fr.enimaloc.catapult.service.enrichment.speedrun;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The world records of a game, one per main full-game category, in the order of the site (the first is the
 * category it shows by default).
 */
public record SpeedrunRecords(SpeedrunGame game, List<CategoryRecord> categories) {

    /**
     * The first place of a category: one {@code time} in seconds, shared by every runner of a tie. {@code timing} is
     * {@code realtime}, {@code realtime_noloads} or {@code ingame}.
     */
    public record CategoryRecord(String categoryId, String category, String url, BigDecimal time, String timing,
                                 List<Runner> runners, LocalDate date, String runUrl, String videoUrl) {
    }

    /** A runner; a guest has no profile, hence a null {@code url}. */
    public record Runner(String name, String url) {
    }
}
