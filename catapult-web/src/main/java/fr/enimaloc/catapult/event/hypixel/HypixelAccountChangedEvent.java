package fr.enimaloc.catapult.event.hypixel;

import fr.enimaloc.catapult.common.dto.channel.HypixelData;
import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The owner's Hypixel settings as they now stand: the Minecraft account they declared (its name and UUID, empty for
 * none) and whether the Hypixel stats can be served on this instance. No secret is held for a member.
 */
public record HypixelAccountChangedEvent(String username, String name, String uuid, boolean available)
        implements ChannelUpdatedEvent {

    public static HypixelAccountChangedEvent of(String username, HypixelData data) {
        return new HypixelAccountChangedEvent(username, data.name() == null ? "" : data.name(),
                data.uuid() == null ? "" : data.uuid(), data.available());
    }
}
