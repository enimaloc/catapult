package fr.enimaloc.catapult.service.enrichment.steamstats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The shapes the personal stats of a member share across Steam, Xbox and RetroAchievements. */
public final class PersonalStats {

    private PersonalStats() {
    }

    /** What could be said of the member on one platform; serialized in lowercase ({@code not_owned}). */
    public enum Status {
        @JsonProperty("ok") OK,
        @JsonProperty("private") PRIVATE,
        @JsonProperty("not_owned") NOT_OWNED,
        @JsonProperty("no_achievements") NO_ACHIEVEMENTS,
        @JsonProperty("unsupported") UNSUPPORTED,
        @JsonProperty("unavailable") UNAVAILABLE
    }

    /**
     * One achievement. A locked secret one carries no name nor description; {@code rarity} is the percentage of
     * players who unlocked it, {@code points} the platform's own score for it (null when it has none).
     */
    public record Achievement(String id, String name, String description, String iconUrl, boolean unlocked,
                              Instant unlockedAt, BigDecimal rarity, Integer points, boolean secret) {
    }

    /** How many achievements are unlocked, the five latest ones, and the rarest one unlocked. */
    public record AchievementSummary(int unlocked, int total, BigDecimal percent, List<Achievement> recent,
                                     Achievement rarest) {

        static final int RECENT = 5;

        public static AchievementSummary of(List<Achievement> all) {
            List<Achievement> unlocked = all.stream().filter(Achievement::unlocked).toList();
            BigDecimal percent = all.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.valueOf(unlocked.size()).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(all.size()), 1, RoundingMode.HALF_UP);
            List<Achievement> recent = unlocked.stream()
                    .sorted(Comparator.comparing(Achievement::unlockedAt,
                            Comparator.nullsLast(Comparator.<Instant>naturalOrder().reversed())))
                    .limit(RECENT).toList();
            Achievement rarest = unlocked.stream().filter(a -> a.rarity() != null)
                    .min(Comparator.comparing(Achievement::rarity)).orElse(null);
            return new AchievementSummary(unlocked.size(), all.size(), percent, recent, rarest);
        }
    }

    /**
     * The member's stats on one game of one platform. {@code all} (every achievement) is only filled for the
     * detail; {@code extra} holds what only some platforms have (gamerscore, points, award…).
     */
    public record PlatformStats(Status status, String id, Long playtimeMinutes, Long playtimeTwoWeeksMinutes,
                                Instant lastPlayed, AchievementSummary achievements, List<Achievement> all,
                                Map<String, Object> extra) {

        /** A stats object that says only {@code status}. */
        public static PlatformStats of(Status status, String id) {
            return new PlatformStats(status, id, null, null, null, null, null, Map.of());
        }
    }
}
