package fr.enimaloc.catapult.service.drops;

import java.time.Instant;
import java.util.Set;

/**
 * A Twitch drops campaign as ttvdrops lists it: its game is a Twitch category ({@code gameId}); a restricted one only
 * runs for {@code allowedChannelIds} (Twitch user ids). {@code imageUrl} is absolute, null when it has none.
 */
public record DropCampaign(String id, String name, String gameId, String gameName, Instant startAt, Instant endAt,
                           String detailsUrl, String imageUrl, boolean restricted, Set<String> allowedChannelIds) {

    /** Whether it runs at {@code now} for the channel of Twitch user id {@code channelTwitchId} (null: unknown). */
    public boolean runningFor(String channelTwitchId, Instant now) {
        boolean open = startAt != null && endAt != null && !now.isBefore(startAt) && now.isBefore(endAt);
        return open && (!restricted || channelTwitchId != null && allowedChannelIds.contains(channelTwitchId));
    }
}
