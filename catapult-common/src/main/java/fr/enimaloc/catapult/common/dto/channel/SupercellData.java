package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's Supercell settings, for the settings card: per game, the player tag they declared ({@code #2PP}, null
 * for none), the player name Supercell gave for it, and whether that game's stats can be served on this instance
 * ({@code available}: the source enabled, with a key of the game in the shared pool). No secret is ever held for a
 * member.
 */
public record SupercellData(
        Game clashOfClans,
        Game clashRoyale,
        Game brawlStars
) {
    /** One game's declared player. */
    public record Game(String tag, String name, boolean available) {}
}
