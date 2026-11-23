package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardPublisher;
import fr.enimaloc.catapult.service.enrichment.bungie.BungiePublisher;

/**
 * The public data of the publishers of the game played now (Blizzard, Bungie), each null when the publisher does not
 * apply (not its game, source off, no application credential, failed, too late). Public game data only: nothing here
 * is about a member.
 */
public record PublisherEnrichment(BlizzardPublisher blizzard, BungiePublisher bungie) {

    /** Blizzard's alone. */
    public PublisherEnrichment(BlizzardPublisher blizzard) {
        this(blizzard, null);
    }

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard) {
        return of(blizzard, null);
    }

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard, BungiePublisher bungie) {
        return blizzard == null && bungie == null ? null : new PublisherEnrichment(blizzard, bungie);
    }
}
