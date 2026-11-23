package fr.enimaloc.catapult.service.enrichment.hltb;

/**
 * How long a game takes to beat, in seconds, as IGDB reports it: rushing the main story ({@code hastily}), with some
 * extras ({@code normally}) and to 100 % ({@code completely}); a {@code null} duration is unknown. {@code count} is
 * the number of submissions behind the figures.
 */
public record TimeToBeat(Long hastily, Long normally, Long completely, int count) {

    /** True when at least one duration is known. */
    public boolean hasAnyDuration() {
        return hastily != null || normally != null || completely != null;
    }
}
