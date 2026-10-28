package fr.enimaloc.catapult.common.dto.channel;

import java.util.Set;

/** A game binding; {@code boxArtUrl} is its Twitch category's box art as Twitch names it, null when not known. */
public record BindingDto(
        String id,
        String status,
        String sourceType,
        String sourceName,
        String twitchGameId,
        String twitchGameName,
        boolean ignored,
        boolean cclEnabled,
        Set<String> ccls,
        boolean twEnabled,
        boolean twOverride,
        Set<String> tws,
        String boxArtUrl
) {
    /** A binding whose category's box art isn't known (live events, the mock). */
    public BindingDto(String id, String status, String sourceType, String sourceName, String twitchGameId,
                      String twitchGameName, boolean ignored, boolean cclEnabled, Set<String> ccls,
                      boolean twEnabled, boolean twOverride, Set<String> tws) {
        this(id, status, sourceType, sourceName, twitchGameId, twitchGameName, ignored, cclEnabled, ccls,
                twEnabled, twOverride, tws, null);
    }
}
