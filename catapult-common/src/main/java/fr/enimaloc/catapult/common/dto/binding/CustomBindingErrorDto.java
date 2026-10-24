package fr.enimaloc.catapult.common.dto.binding;

/**
 * Why triggering a custom binding did not change the channel: a stable {@code code} the web translates, and a
 * readable {@code message} for the API's callers (a Stream Deck shows it as is).
 */
public record CustomBindingErrorDto(String code, String message) {

    /** 409: no Twitch token, or Twitch refused it. */
    public static final String TWITCH_UNAVAILABLE = "twitch_unavailable";
    /** 409: the bot is paused on the channel, Catapult does not touch it. */
    public static final String BOT_DISABLED = "bot_disabled";
    /** 502: Twitch failed or refused the update. */
    public static final String TWITCH_FAILED = "twitch_failed";
    /** 409 on create or edit: another binding of the channel already has that name. */
    public static final String NAME_TAKEN = "name_taken";
    /** 409 on create: the channel already has the maximum number of custom bindings. */
    public static final String LIMIT_REACHED = "limit_reached";
    /** 400 on create or edit: empty or too long name, missing category, invalid tags. */
    public static final String INVALID = "invalid";
}
