package fr.enimaloc.catapult.common.dto.binding;

/**
 * An edited custom binding, always saved. {@code reapplyOutcome} is null unless it is the active one, which is then
 * re-applied to Twitch at once: one of the {@link CustomBindingErrorDto} codes, or {@link #APPLIED}.
 */
public record CustomBindingSaveResult(CustomBindingDto binding, String reapplyOutcome) {

    public static final String APPLIED = "applied";
}
