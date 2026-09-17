package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.service.SteamStoreService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Parses {@link SteamStoreService.SteamStorePage.ReleaseDate#date()} into an {@link Instant}.
 * Shared between GameInfoResponse.SteamObject and SteamDetailResponse.Page so both expose a
 * proper Instant instead of Steam's raw, locale-formatted display string.
 *
 * <p>Steam formats this field in the locale the appdetails request was made with ("l=" query
 * param — see SteamStoreServiceImpl/SteamLanguages, which covers 26 languages), so parsing needs
 * that same Locale for month names to resolve, not the JVM default. Verified against live Steam
 * responses (Portal, appId 400) for all 26 SteamLanguages locales except Ukrainian/Dutch/Danish/
 * Finnish/Norwegian/Swedish/Hungarian/Czech/Romanian/Bulgarian/Greek/Vietnamese/Indonesian/
 * Turkish, which weren't spot-checked but share one of the covered shapes (Latin abbreviated
 * month, comma-less "d MMM uuuu") closely enough to likely already work:
 * en "21 Nov, 2019" · de "21. Nov. 2019" · es "21 OCT 2019" (uppercase — case-insensitive parsing
 * handles this) · ru "21 окт. 2019 г." · pl "21 października 2019" (full month name, not
 * abbreviated) · ar "21 أكتوبر, 2019" (full month + comma) · zh "2019 年 10 月 21 日" (spaced) ·
 * ja "2019年10月21日" (no spaces) · ko "2019년 10월 21일" · th "21 ต.ค. 2019" · pt-BR
 * "21/nov./2019" (this last one from memory, not spot-checked live).
 */
final class SteamReleaseDate {

    private static final List<String> PATTERNS = List.of(
            "d MMM, uuuu",              // English
            "d'.' MMM uuuu",            // German (short month name already ends in ".")
            "d MMM uuuu",               // French/Italian/Spanish(uppercase)/Thai/most others
            "d MMMM uuuu",              // Polish (full, not abbreviated, month name)
            "d MMMM, uuuu",             // Arabic (full month name + comma)
            "d/MMM/uuuu",               // Brazilian Portuguese — unverified
            "d MMM uuuu 'г.'",          // Russian (trailing "г." = "year")
            "uuuu'年'M'月'd'日'",         // Japanese (no spaces around 年/月/日)
            "uuuu '年' M '月' d '日'",    // Chinese (spaced around 年/月/日)
            "uuuu'년' M'월' d'일'");      // Korean

    private SteamReleaseDate() {}

    /** Null when there's no date yet (comingSoon with no confirmed date) or no pattern matches. */
    static Instant parse(SteamStoreService.SteamStorePage.ReleaseDate releaseDate, Locale locale) {
        if (releaseDate == null || releaseDate.date() == null || releaseDate.date().isBlank()) {
            return null;
        }
        Locale parseLocale = locale == null ? Locale.ENGLISH : locale;
        for (String pattern : PATTERNS) {
            try {
                DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                        .parseCaseInsensitive().appendPattern(pattern).toFormatter(parseLocale);
                return LocalDate.parse(releaseDate.date(), formatter).atStartOfDay(ZoneId.of("UTC")).toInstant();
            } catch (DateTimeParseException ignored) {
                // try the next pattern
            }
        }
        return null;
    }
}
