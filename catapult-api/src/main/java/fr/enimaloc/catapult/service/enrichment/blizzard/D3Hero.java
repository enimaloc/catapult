package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.time.Instant;

/** A Diablo III hero of a profile. */
public record D3Hero(long id, String name, String className, int level, int paragonLevel, boolean hardcore,
                     boolean seasonal, boolean dead, Instant lastUpdated) {
}
