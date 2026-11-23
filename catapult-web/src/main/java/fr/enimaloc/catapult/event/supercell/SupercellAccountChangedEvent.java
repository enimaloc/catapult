package fr.enimaloc.catapult.event.supercell;

import fr.enimaloc.catapult.common.dto.channel.SupercellData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's Supercell settings as they now stand, per game: the player tag they declared (empty for none), how
 * the card shows it ({@code Name (#TAG)}, empty for none) and whether that game's stats can be served on this
 * instance. No secret is held for a member.
 */
public record SupercellAccountChangedEvent(String username,
                                           String clashOfClansTag, String clashOfClansPlayer,
                                           boolean clashOfClansAvailable,
                                           String clashRoyaleTag, String clashRoyalePlayer,
                                           boolean clashRoyaleAvailable,
                                           String brawlStarsTag, String brawlStarsPlayer,
                                           boolean brawlStarsAvailable) implements ChannelUpdatedEvent {

    public static SupercellAccountChangedEvent of(String username, SupercellData data) {
        SupercellData.Game coc = orNone(data.clashOfClans());
        SupercellData.Game cr = orNone(data.clashRoyale());
        SupercellData.Game bs = orNone(data.brawlStars());
        return new SupercellAccountChangedEvent(username, tag(coc), player(coc), coc.available(), tag(cr), player(cr),
                cr.available(), tag(bs), player(bs), bs.available());
    }

    private static SupercellData.Game orNone(SupercellData.Game game) {
        return game == null ? new SupercellData.Game(null, null, false) : game;
    }

    private static String tag(SupercellData.Game game) {
        return game.tag() == null ? "" : game.tag();
    }

    /** {@code Name (#TAG)}, the tag alone without a name, empty for none. */
    public static String player(SupercellData.Game game) {
        if (game == null || game.tag() == null || game.tag().isBlank()) {
            return "";
        }
        return game.name() == null || game.name().isBlank() ? game.tag() : game.name() + " (" + game.tag() + ")";
    }
}
