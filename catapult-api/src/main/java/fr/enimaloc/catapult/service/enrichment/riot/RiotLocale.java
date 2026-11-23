package fr.enimaloc.catapult.service.enrichment.riot;

import java.util.Locale;
import java.util.Map;

/** The Data Dragon locale ({@code fr_FR}…) of a request's language; {@code en_US} by default. */
public final class RiotLocale {

    private static final Map<String, String> BY_LANGUAGE = Map.ofEntries(
            Map.entry("fr", "fr_FR"), Map.entry("de", "de_DE"), Map.entry("es", "es_ES"), Map.entry("it", "it_IT"),
            Map.entry("pt", "pt_BR"), Map.entry("ru", "ru_RU"), Map.entry("pl", "pl_PL"), Map.entry("tr", "tr_TR"),
            Map.entry("ko", "ko_KR"), Map.entry("ja", "ja_JP"), Map.entry("zh", "zh_CN"), Map.entry("cs", "cs_CZ"),
            Map.entry("el", "el_GR"), Map.entry("hu", "hu_HU"), Map.entry("ro", "ro_RO"), Map.entry("th", "th_TH"),
            Map.entry("vi", "vi_VN"), Map.entry("id", "id_ID"), Map.entry("ar", "ar_AE"));

    private RiotLocale() {
    }

    public static String of(Locale locale) {
        if (locale == null) {
            return "en_US";
        }
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        if (language.equals("en")) {
            return "GB".equalsIgnoreCase(locale.getCountry()) ? "en_GB" : "en_US";
        }
        return BY_LANGUAGE.getOrDefault(language, "en_US");
    }
}
