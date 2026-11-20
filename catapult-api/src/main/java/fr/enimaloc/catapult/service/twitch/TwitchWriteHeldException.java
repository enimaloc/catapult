package fr.enimaloc.catapult.service.twitch;

/**
 * A write to Twitch held back for now, not made: nothing changed on the channel, and the same write may be tried
 * again later (who is in dry run is not known yet, for one).
 */
public class TwitchWriteHeldException extends IllegalStateException {

    public TwitchWriteHeldException(String message) {
        super(message);
    }
}
