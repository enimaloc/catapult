package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardPublisher;
import fr.enimaloc.catapult.service.enrichment.bungie.BungiePublisher;
import fr.enimaloc.catapult.service.enrichment.hypixel.HypixelPublisher;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPublisher;

/**
 * The public data of the publishers of the game played now (Blizzard, Bungie, Riot, Hypixel), each null when the publisher does
 * not apply (not its game, source off, no application credential, failed, too late). Public game data only: nothing
 * here is about a member.
 */
public record PublisherEnrichment(BlizzardPublisher blizzard, BungiePublisher bungie, RiotPublisher riot,
                                  HypixelPublisher hypixel) {

    /** Blizzard's alone. */
    public PublisherEnrichment(BlizzardPublisher blizzard) {
        this(blizzard, null, null, null);
    }

    /** Without Riot's and Hypixel's data. */
    public PublisherEnrichment(BlizzardPublisher blizzard, BungiePublisher bungie) {
        this(blizzard, bungie, null, null);
    }

    /** Without Hypixel's data. */
    public PublisherEnrichment(BlizzardPublisher blizzard, BungiePublisher bungie, RiotPublisher riot) {
        this(blizzard, bungie, riot, null);
    }

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard) {
        return of(blizzard, null, null);
    }

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard, BungiePublisher bungie) {
        return of(blizzard, bungie, null);
    }

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard, RiotPublisher riot) {
        return of(blizzard, null, riot);
    }

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard, BungiePublisher bungie, RiotPublisher riot) {
        return of(blizzard, bungie, riot, null);
    }

    /** The data of the publishers that apply; null when none does. */
    public static PublisherEnrichment of(BlizzardPublisher blizzard, BungiePublisher bungie, RiotPublisher riot,
                                         HypixelPublisher hypixel) {
        return blizzard == null && bungie == null && riot == null && hypixel == null ? null
                : new PublisherEnrichment(blizzard, bungie, riot, hypixel);
    }
}
