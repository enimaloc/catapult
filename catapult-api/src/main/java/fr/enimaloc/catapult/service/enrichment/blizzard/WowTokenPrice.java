package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.time.Instant;

/** The WoW Token price of a region, in gold. */
public record WowTokenPrice(long gold, Instant updatedAt) {
}
