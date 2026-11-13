package fr.enimaloc.catapult.common.dto.admin;

/**
 * An IGDB game offered by the game mapping form's search: its ID and name, its release year and cover image
 * when IGDB has them (null otherwise).
 */
public record IgdbGameOption(String id, String name, Integer releaseYear, String coverUrl) {}
