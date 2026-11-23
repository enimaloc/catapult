package fr.enimaloc.catapult.service.enrichment.supercell;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Supercell player tag as Supercell spells it: {@code #} then 3 to 12 of the characters its tags are made of
 * ({@code 0289PYLQGRJCUV}). What a member types is normalized first: blanks dropped, upper case, a missing {@code #}
 * added, and the letter O read as the digit 0 (tags have no O; players often type one for the zero).
 */
public final class SupercellTag {

    /** The characters after the {@code #}. */
    static final Pattern BODY = Pattern.compile("[0289PYLQGRJCUV]{3,12}");
    /** What a member may type before normalization: at most a few blanks around a short tag. */
    static final int MAX_INPUT = 32;

    private SupercellTag() {
    }

    /** The tag {@code raw} stands for ({@code #2PP}); empty when it cannot be one. */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.length() > MAX_INPUT) {
            return Optional.empty();
        }
        String body = raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT).replace('O', '0');
        if (body.startsWith("#")) {
            body = body.substring(1);
        }
        return BODY.matcher(body).matches() ? Optional.of("#" + body) : Optional.empty();
    }

    /** A normalized tag as a URL path segment: its {@code #} encoded ({@code %232PP}). */
    static String pathSegment(String tag) {
        if (tag == null || !tag.startsWith("#") || !BODY.matcher(tag.substring(1)).matches()) {
            throw new IllegalArgumentException("not a normalized Supercell tag");
        }
        return "%23" + tag.substring(1);
    }
}
