package fr.enimaloc.catapult.api.userapi;

import java.util.List;
import java.util.Locale;

/** Locale resolution shared by the user API versions. */
final class RequestLocale {

    /** The Swagger description of the {@code lang} parameter. */
    static final String LANG_DESCRIPTION = "BCP 47 language tag used to localize/re-fetch "
            + "platform data (Steam store text and date format, trigger warning labels, Xbox "
            + "store text). Falls back to the Accept-Language request header, then English. For "
            + "the list of locales Steam actually supports, see the \"lang\" parameter on GET "
            + "/steam/{appId}.";

    /** The Swagger description of the {@code Accept-Language} header. */
    static final String ACCEPT_LANGUAGE_DESCRIPTION = "Fallback for \"lang\" when that "
            + "query param is omitted — used by clients that can set headers (this route is also "
            + "meant to be pasted as a plain URL into an OBS browser source, which sends no "
            + "custom headers, hence \"lang\" taking priority). The highest-quality (\"q=\") tag "
            + "is used; unparseable values are ignored.";

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
