package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardStats;
import fr.enimaloc.catapult.service.enrichment.bungie.BungieStats;
import fr.enimaloc.catapult.service.enrichment.chess.ChessStats;
import fr.enimaloc.catapult.service.enrichment.faceit.FaceitStats;
import fr.enimaloc.catapult.service.enrichment.riot.RiotStats;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.PlatformStats;

/**
 * The member's own stats on the game played now, per platform (Steam, Xbox, RetroAchievements) and publisher
 * (Blizzard, Bungie, Riot, FACEIT, Chess.com and Lichess), each null when it does not apply (no link, no match, source off, too late).
 * {@code detectedOn} is the platform the game was detected on, which the summary takes its numbers from first.
 * Personal data: only ever served under {@code /api/me}.
 */
public record PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                      BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                      BungieStats bungie, RiotStats riot, FaceitStats faceit, ChessStats chess) {

    /** Without the chess stats. */
    public PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                   BlizzardStats blizzard, GameBinding.SourceType detectedOn, BungieStats bungie,
                                   RiotStats riot, FaceitStats faceit) {
        this(steam, xbox, retroachievements, blizzard, detectedOn, bungie, riot, faceit, null);
    }

    /** Without the Bungie, Riot and FACEIT stats. */
    public PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                   BlizzardStats blizzard, GameBinding.SourceType detectedOn) {
        this(steam, xbox, retroachievements, blizzard, detectedOn, null, null, null, null);
    }

    /** Without the Riot and FACEIT stats. */
    public PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                   BlizzardStats blizzard, GameBinding.SourceType detectedOn, BungieStats bungie) {
        this(steam, xbox, retroachievements, blizzard, detectedOn, bungie, null, null, null);
    }

    /** Without the FACEIT stats. */
    public PersonalStatsEnrichment(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                   BlizzardStats blizzard, GameBinding.SourceType detectedOn, BungieStats bungie,
                                   RiotStats riot) {
        this(steam, xbox, retroachievements, blizzard, detectedOn, bungie, riot, null, null);
    }

    /** The stats of the platforms that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, null, null, null, null);
    }

    /** The stats of the platforms and Bungie that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             BungieStats bungie) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, bungie, null, null, null);
    }

    /** The stats of the platforms and Riot that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             RiotStats riot) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, null, riot, null, null);
    }

    /** The stats of the platforms and FACEIT that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             FaceitStats faceit) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, null, null, faceit, null);
    }

    /** The stats of the platforms and chess that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             ChessStats chess) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, null, null, null, chess);
    }

    /** The stats of the platforms, Bungie and Riot that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             BungieStats bungie, RiotStats riot) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, bungie, riot, null, null);
    }

    /** The stats of the platforms and publishers that apply; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             BungieStats bungie, RiotStats riot, FaceitStats faceit) {
        return of(steam, xbox, retroachievements, blizzard, detectedOn, bungie, riot, faceit, null);
    }

    /** The stats of the platforms and publishers that apply, chess included; null when none does. */
    public static PersonalStatsEnrichment of(PlatformStats steam, PlatformStats xbox, PlatformStats retroachievements,
                                             BlizzardStats blizzard, GameBinding.SourceType detectedOn,
                                             BungieStats bungie, RiotStats riot, FaceitStats faceit,
                                             ChessStats chess) {
        if (steam == null && xbox == null && retroachievements == null && blizzard == null && bungie == null
                && riot == null && faceit == null && chess == null) {
            return null;
        }
        return new PersonalStatsEnrichment(steam, xbox, retroachievements, blizzard, detectedOn, bungie, riot,
                faceit, chess);
    }
}
