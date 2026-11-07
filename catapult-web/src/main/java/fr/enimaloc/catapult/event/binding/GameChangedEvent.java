package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

public record GameChangedEvent(
        String username,
        String bindingId,
        String sourceType,
        String sourceName
) implements ChannelUpdatedEvent {
}
