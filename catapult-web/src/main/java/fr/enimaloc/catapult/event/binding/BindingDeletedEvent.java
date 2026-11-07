package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;


public record BindingDeletedEvent(String username, String bindingId) implements ChannelUpdatedEvent {
}
