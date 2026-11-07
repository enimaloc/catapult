package fr.enimaloc.catapult.common.dto.admin;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a configured meta tag must be, for catapult-api and catapult-web's mock alike. A key is a plain
 * name (letters, digits, {@code : . _ -}, starting with a letter); the ones the site already writes
 * itself are refused, so that a page never carries two conflicting copies. A content is one line, not blank.
 */
public final class MetaTagRules {
    public static final int MAX_KEY = 100;
    public static final int MAX_CONTENT = 1000;
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9:._-]*");
    /** Written by catapult-web's index.html itself (compared lowercased). */
    public static final Set<String> RESERVED = Set.of("viewport", "description", "robots", "og:type", "og:site_name",
            "og:title", "og:description", "og:url", "twitter:card", "sentry-trace", "baggage", "darkreader-lock");

    private MetaTagRules() {
    }

    /** Why {@code request} is refused ({@code Missing attribute}, {@code Invalid key}…), null when it is valid. */
    public static String problem(MetaTagRequest request) {
        if (request == null || request.attribute() == null) return "Missing attribute";
        String key = request.key();
        if (key == null || key.length() > MAX_KEY || !KEY.matcher(key).matches()) return "Invalid key";
        if (RESERVED.contains(key.toLowerCase(Locale.ROOT))) return "Reserved key";
        String content = request.content();
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT
                || content.chars().anyMatch(MetaTagRules::breaksTheLine)) return "Invalid content";
        return null;
    }

    private static boolean breaksTheLine(int c) {
        return Character.isISOControl(c) || c == ' ' || c == ' ';
    }
}
