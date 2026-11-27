package fr.enimaloc.catapult.domain.account;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The scopes of an {@link OAuthToken} as stored: space-separated. Twitch answers them as a JSON array, which Spring's
 * token response converter reads as its {@code toString()} split on spaces ({@code "[a,"}, {@code "b]"}): brackets
 * and commas are dropped here, when stored and when read (a value stored before is read right too).
 */
public final class OAuthScopes {

    private OAuthScopes() {
    }

    /** {@code scopes} as stored: cleaned, space-separated. */
    public static String join(Collection<String> scopes) {
        return String.join(" ", parse(String.join(" ", scopes)));
    }

    /** The scopes of a stored value, brackets and commas left out; none for null or blank. */
    public static Set<String> parse(String stored) {
        if (stored == null) return Set.of();
        return Arrays.stream(stored.split("[\\s,\\[\\]]+"))
                .filter(scope -> !scope.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
