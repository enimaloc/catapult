package fr.enimaloc.catapult.service.enrichment;

import java.text.Normalizer;
import java.util.Locale;

/** Name matching helpers shared by the enrichment sources that look a game up by its title. */
public final class GameNames {

    private GameNames() {}

    /** Lower case, accents and trademark signs dropped, punctuation removed, spaces collapsed. */
    public static String normalize(String name) {
        String folded = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[™®©]", "");
        StringBuilder kept = new StringBuilder(folded.length());
        folded.codePoints()
                .filter(c -> Character.isLetterOrDigit(c) || Character.isWhitespace(c))
                .forEach(kept::appendCodePoint);
        return kept.toString().replaceAll("\\s+", " ").strip();
    }
}
