package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import org.springframework.context.ApplicationEvent;

public record BindingDeletedEvent(String username, String bindingId) implements ChannelUpdatedEvent {
}
