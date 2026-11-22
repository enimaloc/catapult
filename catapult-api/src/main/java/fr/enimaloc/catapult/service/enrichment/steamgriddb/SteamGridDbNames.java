package fr.enimaloc.catapult.service.enrichment.steamgriddb;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Game names as SteamGridDB is asked and answered about them. Conservative on purpose: only trademark signs are dropped
 * from the search and the cache key; a trailing "edition" marker is only dropped as a fallback, and a match on the
 * full name always wins over one on the stripped name, so a remake is not folded into the original.
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
     * The name cleaned for a search and for the cache key: lower case, without trademark signs, slashes (they would
     * split the URL path) turned into spaces, whitespace collapsed. The edition is kept: a "Definitive Edition" is
     * often a remake with artwork of its own.
     */
    static String fullTerm(String name) {
        return squash(SLASHES.matcher(TRADEMARKS.matcher(name).replaceAll("")).replaceAll(" "))
                .toLowerCase(Locale.ROOT);
    }

    /**
     * {@link #fullTerm} without one trailing edition marker, the fallback when the full name finds nothing; a name
     * that would become empty is left as it is.
     */
    static String strippedTerm(String name) {
        String full = fullTerm(name);
        String stripped = squash(EDITION.matcher(full).replaceFirst(""));
        return stripped.isEmpty() ? full : stripped;
    }

    /** Two names are the same product when their keys are equal: no case, accents or punctuation, edition kept. */
    static String key(String name) {
        return fold(fullTerm(name));
    }

    /** As {@link #key} with the trailing edition marker dropped: "Mafia: Definitive Edition" and "Mafia" agree. */
    static String strippedKey(String name) {
        return fold(strippedTerm(name));
    }

    private static String fold(String term) {
        String decomposed = Normalizer.normalize(term, Normalizer.Form.NFD);
        return squash(NOT_ALPHANUMERIC.matcher(MARKS.matcher(decomposed).replaceAll("")).replaceAll(" "));
    }

    private static String squash(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }
}
