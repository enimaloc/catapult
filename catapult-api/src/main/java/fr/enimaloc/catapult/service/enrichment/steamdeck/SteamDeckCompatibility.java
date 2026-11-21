package fr.enimaloc.catapult.service.enrichment.steamdeck;

import java.util.List;

/**
 * Steam Deck compatibility verdict of a Steam app, with the individual checks behind it.
 *
 * @param category the resolved verdict
 * @param items    the tested items that led to it (possibly empty)
 */
public record SteamDeckCompatibility(Category category, List<TestedItem> items) {

    /** Verdict, numbered like Steam's {@code resolved_category}. */
    public enum Category {
        UNKNOWN, UNSUPPORTED, PLAYABLE, VERIFIED;

        /** The category for a Steam code (0..3); any other code is {@link #UNKNOWN}. */
        public static Category fromCode(int code) {
            return switch (code) {
                case 1 -> UNSUPPORTED;
                case 2 -> PLAYABLE;
                case 3 -> VERIFIED;
                default -> UNKNOWN;
            };
        }
    }

    /**
     * One tested item.
     *
     * @param result Steam's {@code display_type} (a number) in its string form
     * @param token  Steam's {@code loc_token} localization key
     */
    public record TestedItem(String result, String token) {
    }
}
