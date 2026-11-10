package fr.enimaloc.catapult.common.dto.binding;

import java.util.List;
import java.util.UUID;

/**
 * A streamer's custom binding as the API answers it (CCLs and TWs sorted); {@code title} is its own stream title,
 * null when it uses the channel-wide one.
 */
public record CustomBindingDto(
        UUID id,
        String name,
        String twitchGameId,
        String twitchGameName,
        List<String> ccls,
        List<String> tws,
        boolean uninterruptible,
        String title
) {
    /** A custom binding without a title of its own. */
    public CustomBindingDto(UUID id, String name, String twitchGameId, String twitchGameName, List<String> ccls,
                            List<String> tws, boolean uninterruptible) {
        this(id, name, twitchGameId, twitchGameName, ccls, tws, uninterruptible, null);
    }
}
