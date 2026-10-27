package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/** The channel's active custom binding changed: {@code activeId} is the one now active, null once deactivated. */
public record CustomBindingChangedEvent(String username, String activeId) implements ChannelUpdatedEvent {
}
