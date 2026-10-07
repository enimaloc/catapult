package fr.enimaloc.catapult.event;

import org.springframework.context.ApplicationEvent;

public record MinecraftDisconnectedEvent(String username) implements ChannelUpdatedEvent {
}
