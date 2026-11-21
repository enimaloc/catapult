package fr.enimaloc.catapult.service.enrichment.hltb;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How long a game takes to beat, in seconds: rushing the main story ({@code hastily}), with some extras
 * ({@code normally}) and to 100 % ({@code completely}); a {@code null} duration is unknown. {@code count} is the
 * number of submissions behind the figures, {@code hltbUrl} the HowLongToBeat page when the data came from there.
 */
public record TimeToBeat(Long hastily, Long normally, Long completely, int count, Origin origin, String hltbUrl) {

    /** Where the figures came from. */
    public enum Origin {
        IGDB, HLTB;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** True when at least one duration is known. */
    public boolean hasAnyDuration() {
        return hastily != null || normally != null || completely != null;
    }
}
