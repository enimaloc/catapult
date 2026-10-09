package fr.enimaloc.catapult.api.userapi;

import java.util.List;
import java.util.Locale;

/** Locale resolution shared by the user API versions. */
final class RequestLocale {

    private RequestLocale() {
    }

    // "lang" wins when present (it's the mechanism that works from a plain pasted OBS URL, with
    // no header control); Accept-Language is the fallback for clients that do set headers. A
    // request with neither, or an unparseable Accept-Language value, gets English — deliberately
    // not the JVM/server default locale, which would make behavior depend on the deploy
    // environment instead of being a documented, stable contract.
    static Locale parse(String lang, String acceptLanguage) {
        if (lang != null && !lang.isBlank()) {
            return Locale.forLanguageTag(lang);
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
                if (!ranges.isEmpty()) {
                    return Locale.forLanguageTag(ranges.getFirst().getRange());
                }
            } catch (IllegalArgumentException ignored) {
                // malformed Accept-Language header — fall through to the default
            }
        }
        return Locale.ENGLISH;
    }
}
