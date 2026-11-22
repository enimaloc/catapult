package fr.enimaloc.catapult.service.enrichment.blizzard;

/** A showcased ladder of a StarCraft II profile; every field but the counts may be missing. */
public record Sc2LadderEntry(String mode, String league, String division, Integer rank, int wins, int losses) {
}
