package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/**
 * The channel's active custom binding changed: {@code activeId} is the one now active, null once deactivated.
 * {@code ts}: as {@link CustomBindingsUpdatedEvent}'s, which marks the active one too.
 */
public record CustomBindingChangedEvent(String username, String activeId, Long ts) implements ChannelUpdatedEvent {

    /** A change at no known time. */
    public CustomBindingChangedEvent(String username, String activeId) {
        this(username, activeId, null);
    }
}
