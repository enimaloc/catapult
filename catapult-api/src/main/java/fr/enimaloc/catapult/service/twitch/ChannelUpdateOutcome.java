package fr.enimaloc.catapult.service.twitch;

/** What {@link TwitchService#updateChannelReporting} did with a channel. */
public enum ChannelUpdateOutcome {
    /** Twitch accepted the new category. */
    APPLIED,
    /** Nothing to send: bot disabled, account not active, binding INCOMPLETE or ignored, nothing configured. */
    SKIPPED,
    /** The channel should have changed but did not: no Twitch token, or Twitch refused or failed the update. */
    FAILED
}
