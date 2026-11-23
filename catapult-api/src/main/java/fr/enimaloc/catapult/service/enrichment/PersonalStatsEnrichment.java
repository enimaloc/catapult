package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieStats;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;

/**
 * The member's own stats on the game played now, per platform (Steam, Xbox, RetroAchievements) and publisher
 * (Blizzard, Bungie, Riot), each null when it does not apply (no link, no match, source off, too late).
 * {@code detectedOn} is the platform the game was detected on, which the summary takes its numbers from first.
 * Personal data: only ever served under {@code /api/me}.
 */
public record PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                      BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                      BungieStats bungie, RiotStats riot) {

    /** Without the Bungie and Riot stats. */
    public PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                   BlizzardStats blizzard, GameBinding.SourceType detectedOn) {
        this(steam, xbox, retroachievements, blizzard, detectedOn, null, null);
    }

    /** Without the Riot stats. */
    public PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                   BlizzardStats blizzard, GameBinding.SourceType detectedOn, BungieStats bungie) {
        this(steam, xbox, retroachievements, blizzard, detectedOn, bungie, null);
    }

    /** The stats of the platforms that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, null, null);
    }

    /** The stats of the platforms and Bungie that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             BungieStats bungie) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, bungie, null);
    }

    /** The stats of the platforms and Riot that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             RiotStats riot) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, null, riot);
    }

    /** The stats of the platforms and publishers that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             BungieStats bungie, RiotStats riot) {
        if (steam == null && xbox == null && retroachievements == null && blizzard == null && bungie == null
                && riot == null) {
            return null;
        }
        return new PersonalStatsEnrichment(steam, xbox, retroachievements, blizzard, detectedOn, bungie, riot);
    }
}
