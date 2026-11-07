package fr.enimaloc.catapult.event;

import fr.enimaloc.catapult.common.event.ChannelEvents;

/** The channel's settings changed: each settings panel's whole state. */
public record ChannelSettingsUpdatedEvent(String username, ChannelEvents.Settings settings) implements ChannelUpdatedEvent {
}
