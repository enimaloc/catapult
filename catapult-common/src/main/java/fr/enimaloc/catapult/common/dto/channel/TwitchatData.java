package fr.enimaloc.catapult.common.dto.channel;

import java.util.List;

/**
 * Which Twitchat public API the channel's pages speak: {@link #AUTO} to detect it from Twitchat
 * itself, or one of {@code supportedBranches}, forced (catapult-web's twitchat.js).
 */
public record TwitchatData(String branch, List<String> supportedBranches) {

    public static final String AUTO = "auto";
    /** twitchat-protocol.js's protocols: Twitchat's main branch, and its beta one. */
    public static final List<String> SUPPORTED_BRANCHES = List.of("stable", "beta");

    public static TwitchatData of(String branch) {
        return new TwitchatData(branch, SUPPORTED_BRANCHES);
    }

    public static boolean isValid(String branch) {
        return AUTO.equals(branch) || SUPPORTED_BRANCHES.contains(branch);
    }
}
