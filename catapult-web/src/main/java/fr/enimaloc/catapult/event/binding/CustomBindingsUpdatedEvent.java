package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/** The channel's custom bindings were created, changed or deleted: the dashboard reloads their list. */
public record CustomBindingsUpdatedEvent(String username) implements ChannelUpdatedEvent {
}
