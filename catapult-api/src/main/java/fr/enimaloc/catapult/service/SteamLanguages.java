package fr.enimaloc.catapult.service;

import java.util.List;
import java.util.Locale;

/**
 * Maps an ISO 639-1 {@link Locale} to Steam's own store-API language name (the {@code l}
 * parameter of {@code appdetails}, e.g. {@code "french"}) — Steam does not accept ISO codes.
 * Unmapped languages fall back to {@code "english"}, Steam's own default.
 */
public final class SteamLanguages {

    /**
     * BCP 47 tags for every language {@link #fromLocale} maps to a distinct Steam language name
     * (kept in sync with the switch below by hand — there's no reflection-based way to derive
     * this list from a switch expression's case labels). Informational/for API docs only (e.g.
     * the "lang" parameter's Swagger schema on Steam-only endpoints); {@link #fromLocale} itself
     * doesn't consult this list and falls back to English for anything not listed here too.
     */
    public static final List<String> SUPPORTED_LOCALES = List.of(
            "en", "fr", "de", "it", "es", "pt", "pt-BR", "zh", "zh-TW", "zh-HK", "ru", "ja", "ko",
            "th", "tr", "uk", "nl", "da", "fi", "no", "sv", "pl", "hu", "cs", "ro", "bg", "el",
            "vi", "ar", "id");

    private SteamLanguages() {
    }

    static String fromLocale(Locale locale) {
        if (locale == null) return "english";
        return switch (locale.getLanguage()) {
            case "fr" -> "french";
            case "de" -> "german";
            case "it" -> "italian";
            case "es" -> "spanish";
            case "pt" -> "BR".equalsIgnoreCase(locale.getCountry()) ? "brazilian" : "portuguese";
            case "zh" -> "TW".equalsIgnoreCase(locale.getCountry())
                    || "HK".equalsIgnoreCase(locale.getCountry()) ? "tchinese" : "schinese";
            case "ru" -> "russian";
            case "ja" -> "japanese";
            case "ko" -> "koreana";
            case "th" -> "thai";
            case "tr" -> "turkish";
            case "uk" -> "ukrainian";
            case "nl" -> "dutch";
            case "da" -> "danish";
            case "fi" -> "finnish";
            case "no" -> "norwegian";
            case "sv" -> "swedish";
            case "pl" -> "polish";
            case "hu" -> "hungarian";
            case "cs" -> "czech";
            case "ro" -> "romanian";
            case "bg" -> "bulgarian";
            case "el" -> "greek";
            case "vi" -> "vietnamese";
            case "ar" -> "arabic";
            case "id" -> "indonesian";
            default -> "english";
        };
    }
}
