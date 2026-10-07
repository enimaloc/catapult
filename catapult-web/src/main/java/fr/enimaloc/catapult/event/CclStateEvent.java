package fr.enimaloc.catapult.event;

import org.springframework.context.ApplicationEvent;

public record CclStateEvent(String username, String bindingId, boolean enabled) implements ChannelUpdatedEvent {
}
