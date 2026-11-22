package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Game names as SteamGridDB is asked and answered about them. Conservative on purpose: only trademark signs and a
 * trailing "edition" marker are dropped, so that a sequel, a remake or a remaster is never folded into the original.
 */
final class SteamGridDbNames {

    /** A trailing edition marker, with its separator or parentheses: "Game - Definitive Edition", "Game (GOTY)". */
    private static final Pattern EDITION = Pattern.compile(
            "(?i)\\s*(?:[-\\u2013\\u2014:]\\s*|\\(\\s*|\\[\\s*)?(?:the\\s+)?"
                    + "(?:(?:game of the year|goty|definitive|complete|deluxe|digital deluxe|ultimate|gold|standard"
                    + "|premium|collector'?s)\\s+edition|goty)\\s*[)\\]]?\\s*$");
    private static final Pattern TRADEMARKS = Pattern.compile("[\\u2122\\u00AE\\u00A9]");
    private static final Pattern SLASHES = Pattern.compile("[/\\\\]");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SteamGridDbNames() {
    }

    /**
     * The term to search by (also the cache key of a name): lower case, without trademark signs or a trailing
     * edition marker, slashes (they would split the URL path) turned into spaces, whitespace collapsed. A name that
     * would become empty is only cleaned of the signs.
     */
    static String searchTerm(String name) {
        String cleaned = squash(SLASHES.matcher(TRADEMARKS.matcher(name).replaceAll("")).replaceAll(" "));
        String withoutEdition = squash(EDITION.matcher(cleaned).replaceFirst(""));
        return (withoutEdition.isEmpty() ? cleaned : withoutEdition).toLowerCase(Locale.ROOT);
    }

    /** Two names are the same game when their keys are equal: no case, accents, punctuation or edition marker. */
    static String key(String name) {
        String decomposed = Normalizer.normalize(searchTerm(name), Normalizer.Form.NFD);
        return squash(NOT_ALPHANUMERIC.matcher(MARKS.matcher(decomposed).replaceAll("")).replaceAll(" "));
    }

    private static String squash(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }
}
