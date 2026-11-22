package fr.enimaloc.catapult.service.enrichment.speedrun;

import java.util.List;

/**
 * What speedrun.com knows of the game played now: its world records, and for the member, their account, their
 * personal bests and runs waiting for verification on that game. {@code account}, {@code personalBests} and
 * {@code pendingRuns} are null when no account is identified (unknown, not "none"); a list is also null when it
 * could not be read in time.
 */
public record SpeedrunEnrichment(SpeedrunRecords records, SpeedrunIdentity account, List<SpeedrunRun> personalBests,
                                 List<SpeedrunRun> pendingRuns) {
}
