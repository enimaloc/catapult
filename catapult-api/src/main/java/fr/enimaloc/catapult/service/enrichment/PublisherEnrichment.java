package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardPublisher;

/**
 * The public data of the publishers of the game played now (Blizzard), each null when the publisher does not apply
 * (not its game, source off, no application credential, failed, too late). Public game data only: nothing here is
 * about a member.
 */
public record PublisherEnrichment(BlizzardPublisher blizzard) {

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard) {
        return blizzard == null ? null : new PublisherEnrichment(blizzard);
    }
}
