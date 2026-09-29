package fr.enimaloc.catapult.ws.event;

import org.springframework.context.ApplicationEvent;

public record MinecraftDisconnectedEvent(String username) implements ChannelUpdatedEvent {
}
