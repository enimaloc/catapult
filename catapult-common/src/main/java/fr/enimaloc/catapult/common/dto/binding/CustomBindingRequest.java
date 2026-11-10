package fr.enimaloc.catapult.common.dto.binding;

import java.util.Set;

/**
 * A custom binding as the streamer creates or edits it: a display name (trimmed, 1–40 characters, unique per
 * streamer whatever its case), a Twitch category (required), optional CCLs and TWs, whether a detected game
 * may interrupt it, and its own stream title: null leaves it as it is (a form without the title field), blank
 * clears it (back to the channel-wide one).
 */
public record CustomBindingRequest(
        String name,
        String twitchGameId,
        String twitchGameName,
        Set<String> ccls,
        Set<String> tws,
        boolean uninterruptible,
        String title
) {
    /** A request leaving the title as it is. */
    public CustomBindingRequest(String name, String twitchGameId, String twitchGameName, Set<String> ccls,
                                Set<String> tws, boolean uninterruptible) {
        this(name, twitchGameId, twitchGameName, ccls, tws, uninterruptible, null);
    }
}
