package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.util.Locale;

/** The Blizzard locale ({@code fr_FR}) for a request language and a region. */
public final class BlizzardLocale {

    private BlizzardLocale() {
    }

    public static String of(Locale locale, BlizzardRegion region) {
        String language = locale == null ? "" : locale.getLanguage();
        return switch (language) {
            case "fr" -> "fr_FR";
            case "de" -> "de_DE";
            case "es" -> "es_ES";
            case "it" -> "it_IT";
            case "pt" -> "pt_BR";
            case "ru" -> "ru_RU";
            case "ko" -> "ko_KR";
            case "zh" -> "zh_TW";
            default -> region == BlizzardRegion.EU ? "en_GB" : "en_US";
        };
    }
}
