package fr.enimaloc.catapult.service.category;

/**
 * Where a channel's Twitch category comes from, in the spec's priority order.
 *
 * <p>Today's effective order: an active custom binding beats any game binding, a MANUAL one included (only a
 * custom binding marked uninterruptible, or the first detection since catapult-api started, keeps it over a
 * detection); {@code !setgame} and the Twitchat reverts are manual choices, applied directly, which then end the
 * custom binding. The raid restore only acts on its own trigger, so it comes last.</p>
 */
public enum CategoryRule {
    /** {@code !setgame} or a Twitchat revert: the category the streamer just chose. */
    MANUAL_CHOICE,
    /** The active custom binding. */
    CUSTOM_BINDING,
    /**
     * The stored binding of the game played; its status MANUAL / MAPPED / AUTO is the manual choice, the admin
     * mapping or IGDB, resolved by {@code BindingService}.
     */
    GAME_BINDING,
    /** The streamer's category for a game whose binding is INCOMPLETE. */
    INCOMPLETE_FALLBACK,
    /** The streamer's "no game" category, sent as a binding: no game, or the game's binding ignored. */
    NO_GAME_FALLBACK,
    /** The streamer's default category (reset), at the stream's start or end. */
    DEFAULT_CATEGORY,
    /** The binding kept pending while offline, applied at the stream's start. */
    PENDING,
    /** The game an outgoing raid puts back. */
    RAID_RESTORE
}
