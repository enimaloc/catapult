package fr.enimaloc.catapult.service.enrichment.blizzard;

/** A StarCraft II profile of a Battle.net account. */
public record Sc2ProfileRef(int regionId, int realmId, long profileId, String name) {
}
