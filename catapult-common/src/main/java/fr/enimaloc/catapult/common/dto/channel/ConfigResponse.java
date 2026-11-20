package fr.enimaloc.catapult.common.dto.channel;

/**
 * An enabled Twitchat widget's OBS connection, for the widget page's first load (the live updates
 * come as {@link fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig}, same fields).
 * {@code twitchatBranch} is the Twitchat API to speak, see {@link TwitchatData}. {@code debugCapture}: whether the
 * owner is in debug mode, the widget then recording its OBS and Twitchat exchanges for their debug journal; boxed so
 * that a body without it (an older catapult-api) still reads, as false.
 */
public record ConfigResponse(String obsHost, Integer obsPort, String obsPassword, String twitchatBranch,
                             Boolean debugCapture) {

    public ConfigResponse {
        debugCapture = Boolean.TRUE.equals(debugCapture);
    }

    /** An owner not in debug mode. */
    public ConfigResponse(String obsHost, Integer obsPort, String obsPassword, String twitchatBranch) {
        this(obsHost, obsPort, obsPassword, twitchatBranch, false);
    }
}
