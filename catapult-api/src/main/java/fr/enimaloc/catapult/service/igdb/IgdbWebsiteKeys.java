package fr.enimaloc.catapult.service.igdb;

import proto.Website;

import java.util.Locale;
import java.util.Map;

/**
 * Turns an IGDB {@link Website} into the key it's stored under in a game's websites map.
 *
 * <p>IGDB deprecated {@code websites.category} (now unset on virtually every entry) in favour of
 * {@code websites.type}, a reference to the {@code website_types} endpoint. The type ids 1-19
 * are the old category values, so they map back onto the keys the category enum produced
 * ({@code official}, {@code steam}, ...) — the ones {@code {game#store#official}} and friends
 * look up. Newer types (Xbox, PlayStation, ...) fall back to a slug of the type's name.
 */
public final class IgdbWebsiteKeys {

    /** Same sentinel the old {@code WEBSITE_CATEGORY_NULL} produced, for entries with no type. */
    public static final String UNTYPED_KEY = "category_null";

    private static final Map<Long, String> LEGACY_KEYS = Map.ofEntries(
        Map.entry(1L, "official"),
        Map.entry(2L, "wikia"),
        Map.entry(3L, "wikipedia"),
        Map.entry(4L, "facebook"),
        Map.entry(5L, "twitter"),
        Map.entry(6L, "twitch"),
        Map.entry(8L, "instagram"),
        Map.entry(9L, "youtube"),
        Map.entry(10L, "iphone"),
        Map.entry(11L, "ipad"),
        Map.entry(12L, "android"),
        Map.entry(13L, "steam"),
        Map.entry(14L, "reddit"),
        Map.entry(15L, "itch"),
        Map.entry(16L, "epicgames"),
        Map.entry(17L, "gog"),
        Map.entry(18L, "discord"),
        Map.entry(19L, "bluesky"));

    private IgdbWebsiteKeys() {
    }

    public static String key(Website website) {
        if (!website.hasType()) {
            return UNTYPED_KEY;
        }
        String legacy = LEGACY_KEYS.get(website.getType().getId());
        if (legacy != null) {
            return legacy;
        }
        String slug = website.getType().getType().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_|_$", "");
        return slug.isEmpty() ? UNTYPED_KEY : slug;
    }
}
