package fr.enimaloc.catapult.service.twitch;

/** What a {@link TwitchService} category call did: {@code updateChannelReporting}, {@code setCategory}, {@code resetToDefault}. */
public enum ChannelUpdateOutcome {
    /** Twitch accepted the new category. */
    APPLIED,
    /** Nothing to send: bot disabled, account not active, binding INCOMPLETE or ignored, nothing configured. */
    SKIPPED,
    /** The channel should have changed but did not: no Twitch token, or Twitch refused or failed the update. */
    FAILED,
    /**
     * The member is in dry run: the change was logged instead of sent. The rest of Catapult takes it as
     * {@link #APPLIED}, except that nothing is marked as changed by Catapult (Twitch's category did not move).
     */
    DRY_RUN;

    /** Whether the change counts as made: {@link #APPLIED}, or {@link #DRY_RUN} as if it were. */
    public boolean succeeded() {
        return this == APPLIED || this == DRY_RUN;
    }
}
