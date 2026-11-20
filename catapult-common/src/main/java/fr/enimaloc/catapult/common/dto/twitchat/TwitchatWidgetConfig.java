package fr.enimaloc.catapult.common.dto.twitchat;

/**
 * OBS-websocket connection info pushed live to an already-open widget page whenever the
 * streamer edits their Twitchat settings, so the relay can reconnect with the new
 * host/port/password without needing a manual page reload. {@code obsPassword} is the
 * decrypted plaintext — the same trust boundary as the initial page-load config fetch
 * (only reachable by someone who already holds the widget token). {@code twitchatBranch} is
 * the Twitchat API to speak through that connection, see {@code TwitchatData}. {@code debugCapture} tells the
 * page to record its OBS and Twitchat exchanges for the owner's debug journal: true only while the owner is in debug
 * mode (filled by catapult-web for the widget and the member's page; false in the live pushes). Boxed so that a
 * body without it (an older catapult-api) still reads, as false.
 */
public record TwitchatWidgetConfig(String obsHost, Integer obsPort, String obsPassword, String twitchatBranch,
                                   Boolean debugCapture) {

    public TwitchatWidgetConfig {
        debugCapture = Boolean.TRUE.equals(debugCapture);
    }

    /** A connection whose page records nothing. */
    public TwitchatWidgetConfig(String obsHost, Integer obsPort, String obsPassword, String twitchatBranch) {
        this(obsHost, obsPort, obsPassword, twitchatBranch, false);
    }

    /** This connection, its page recording its exchanges or not as {@code capture} says. */
    public TwitchatWidgetConfig withDebugCapture(boolean capture) {
        return new TwitchatWidgetConfig(obsHost, obsPort, obsPassword, twitchatBranch, capture);
    }
}
