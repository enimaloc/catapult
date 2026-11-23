package fr.enimaloc.catapult.event.riot;

import fr.enimaloc.catapult.common.dto.channel.RiotData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's Riot settings as they now stand: the Riot ID they declared ({@code gameName#tagLine}, empty for none),
 * its League of Legends platform ({@code euw1}…, empty for none) and whether the Riot stats can be served on this
 * instance. No secret is held for a member.
 */
public record RiotAccountChangedEvent(String username, String riotId, String platform, boolean available)
        implements ChannelUpdatedEvent {

    public static RiotAccountChangedEvent of(String username, RiotData data) {
        return new RiotAccountChangedEvent(username, data.riotId() == null ? "" : data.riotId(),
                data.platform() == null ? "" : data.platform(), data.available());
    }
}
