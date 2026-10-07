package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import org.springframework.context.ApplicationEvent;

public record CclStateEvent(String username, String bindingId, boolean enabled) implements ChannelUpdatedEvent {
}
