package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.time.Instant;
import java.util.List;

/** The projection of a character's achievements: the points and the ten most recent ones, newest first. */
public record WowAchievements(int points, List<Recent> recent) {

    public WowAchievements {
        recent = List.copyOf(recent);
    }

    public record Recent(String id, String name, Instant unlockedAt) {
    }
}
