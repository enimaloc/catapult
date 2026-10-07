package fr.enimaloc.catapult.event.minecraft;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import org.springframework.context.ApplicationEvent;

public record MinecraftDisconnectedEvent(String username) implements ChannelUpdatedEvent {
}
