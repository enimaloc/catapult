package fr.enimaloc.catapult.service;

import lombok.Getter;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Maps an ISO 639-1 {@link Locale} to Steam's own store-API language name (the {@code l}
 * parameter of {@code appdetails}, e.g. {@code "french"}) — Steam does not accept ISO codes.
 * Unmapped languages fall back to {@code "english"}, Steam's own default.
 */
@Getter
public enum SteamLanguage {
    ENGLISH("en"),
    FRENCH("fr"),
    GERMAN("de"),
    ITALIAN("it"),
    SPANISH("es"),
    BRAZILIAN("pt", locale -> "BR".equalsIgnoreCase(locale.getCountry())),
    PORTUGUESE("pt"),
    TCHINESE("zh", locale -> "TW".equalsIgnoreCase(locale.getCountry())
            || "HK".equalsIgnoreCase(locale.getCountry())),
    SCHINESE("zh"),
    RUSSIAN("ru"),
    JAPANESE("ja"),
    KOREANA("ko"),
    THAI("th"),
    TURKISH("tr"),
    UKRAINIAN("uk"),
    DUTCH("nl"),
    DANISH("da"),
    FINNISH("fi"),
    NORWEGIAN("no"),
    SWEDISH("sv"),
    POLISH("pl"),
    HUNGARIAN("hu"),
    CZECH("cs"),
    ROMANIAN("ro"),
    BULGARIAN("bg"),
    GREEK("el"),
    VIETNAMESE("vi"),
    ARABIC("ar"),
    INDONESIAN("id");

    private final String code;
    private final Predicate<Locale> additionalPredicate;

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

    private SteamLanguage(String code) {
        this(code, unused -> true);
    }

    private SteamLanguage(String code, Predicate<Locale> additionalPredicate) {
        this.code = code;
        this.additionalPredicate = additionalPredicate;
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }

    static SteamLanguage fromLocale(Locale locale) {
        if (locale == null) return ENGLISH;
        for (SteamLanguage value : values()) {
            if (value.getCode().equals(locale.getLanguage()) && value.getAdditionalPredicate().test(locale)) {
                return value;
            }
        }
        return ENGLISH;
    }
}
