package fr.enimaloc.catapult.service.enrichment.blizzard;

/** A Hearthstone card set. */
public record HearthstoneSet(int id, String name, String slug, String type, Integer collectibleCount) {
}
