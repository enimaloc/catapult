package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.domain.binding.GameBinding;

/**
 * What {@link CategoryChain} decided for a channel, before anything is sent: the {@link Action}, the rule that
 * decided it, and what it needs (a binding for {@code UPDATE_CHANNEL} and {@code STORE_PENDING}, an id and a name for
 * {@code SET_CATEGORY}, a reason for {@code NONE}).
 */
public record CategoryDecision(Action action, CategoryRule rule, GameBinding binding, String twitchGameId,
                               String twitchGameName, CategoryOutcome.Reason reason) {

    public enum Action {
        /** {@code TwitchService#updateChannelReporting} of the binding. */
        UPDATE_CHANNEL,
        /** {@code TwitchService#setCategory}: {@code game_id} only. */
        SET_CATEGORY,
        /** {@code TwitchService#resetToDefault}. */
        RESET_TO_DEFAULT,
        /** The binding kept pending until the stream starts. */
        STORE_PENDING,
        /** Nothing sent. */
        NONE
    }

    public static CategoryDecision update(CategoryRule rule, GameBinding binding) {
        return new CategoryDecision(Action.UPDATE_CHANNEL, rule, binding, null, null, CategoryOutcome.Reason.NONE);
    }

    public static CategoryDecision setCategory(CategoryRule rule, String twitchGameId, String twitchGameName) {
        return new CategoryDecision(Action.SET_CATEGORY, rule, null, twitchGameId, twitchGameName,
                CategoryOutcome.Reason.NONE);
    }

    public static CategoryDecision reset(CategoryRule rule) {
        return new CategoryDecision(Action.RESET_TO_DEFAULT, rule, null, null, null, CategoryOutcome.Reason.NONE);
    }

    public static CategoryDecision storePending(CategoryRule rule, GameBinding binding) {
        return new CategoryDecision(Action.STORE_PENDING, rule, binding, null, null, CategoryOutcome.Reason.NONE);
    }

    public static CategoryDecision none(CategoryRule rule, CategoryOutcome.Reason reason) {
        return new CategoryDecision(Action.NONE, rule, null, null, null, reason);
    }
}
