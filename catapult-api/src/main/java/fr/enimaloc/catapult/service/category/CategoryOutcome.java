package fr.enimaloc.catapult.service.category;

import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;

/**
 * What applying a {@link CategoryDecision} did: Twitch's answer ({@code status}), the rule that decided, why nothing
 * was sent when nothing was, and the exception the Twitch layer threw, if any (carried, not thrown: a caller that
 * propagated it before calls {@link #orThrow()}).
 */
public record CategoryOutcome(ChannelUpdateOutcome status, CategoryRule rule, Reason reason, RuntimeException error) {

    public enum Reason {
        NONE,
        CUSTOM_BINDING_ACTIVE,
        OFFLINE,
        STORED_PENDING,
        RAID_HOLD,
        NO_SETTINGS,
        NO_GAME_FALLBACK_OFF,
        NOTHING_CONFIGURED,
        NOT_PLAYED_NOW,
        NOTHING_PENDING,
        DEFAULT_OFF,
        GAME_DETECTED,
        BOT_INACTIVE,
        RAID_RESTORE_OFF,
        NOTHING_TO_RESTORE,
        ALREADY_ON_CATEGORY,
        /** Twitch skipped it (bot off, account inactive, binding ignored or INCOMPLETE...). */
        TWITCH_SKIPPED,
        /** Twitch failed or refused it, or no Twitch token. */
        TWITCH_FAILED,
        /** The Twitch layer threw: see {@link #error()}. */
        ERROR
    }

    public static CategoryOutcome applied(CategoryRule rule) {
        return new CategoryOutcome(ChannelUpdateOutcome.APPLIED, rule, Reason.NONE, null);
    }

    public static CategoryOutcome skipped(CategoryRule rule, Reason reason) {
        return new CategoryOutcome(ChannelUpdateOutcome.SKIPPED, rule, reason, null);
    }

    public static CategoryOutcome failed(CategoryRule rule, Reason reason) {
        return new CategoryOutcome(ChannelUpdateOutcome.FAILED, rule, reason, null);
    }

    public static CategoryOutcome error(CategoryRule rule, RuntimeException e) {
        return new CategoryOutcome(ChannelUpdateOutcome.FAILED, rule, Reason.ERROR, e);
    }

    /** Rethrows the exception the Twitch layer threw, if any; else this outcome. */
    public CategoryOutcome orThrow() {
        if (error != null) throw error;
        return this;
    }
}
