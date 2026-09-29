package fr.enimaloc.catapult.ws.event;

import org.springframework.context.ApplicationEvent;

public record BindingDeletedEvent(String username, String bindingId) implements ChannelUpdatedEvent {
}
