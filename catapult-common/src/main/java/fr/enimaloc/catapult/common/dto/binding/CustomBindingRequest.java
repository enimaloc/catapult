package fr.enimaloc.catapult.common.dto.binding;

import java.util.Set;

/**
 * A custom binding as the streamer creates or edits it: a display name (trimmed, 1–40 characters, unique per
 * streamer whatever its case), a Twitch category (required), optional CCLs and TWs, and whether a detected game
 * may interrupt it.
 */
public record CustomBindingRequest(
        String name,
        String twitchGameId,
        String twitchGameName,
        Set<String> ccls,
        Set<String> tws,
        boolean uninterruptible
) {}
