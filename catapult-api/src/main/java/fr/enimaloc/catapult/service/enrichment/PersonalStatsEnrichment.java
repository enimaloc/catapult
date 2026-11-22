package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;

/**
 * The member's own stats on the game played now, per platform (Steam, Xbox, RetroAchievements) and publisher
 * (Blizzard), each null when it does not apply (no link, no match, source off, too late). {@code detectedOn} is the
 * platform the game was detected on, which the summary takes its numbers from first. Personal data: only ever
 * served under {@code /api/me}.
 */
public record PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                      BlizzardStats blizzard, GameBinding.SourceType detectedOn) {

    /** The stats of the platforms that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn) {
        if (steam == null && xbox == null && retroachievements == null && blizzard == null) {
            return null;
        }
        return new PersonalStatsEnrichment(steam, xbox, retroachievements, blizzard, detectedOn);
    }
}
