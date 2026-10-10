package fr.enimaloc.catapult.event.minecraft;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;


public record MinecraftDisconnectedEvent(String username) implements ChannelUpdatedEvent {
}
