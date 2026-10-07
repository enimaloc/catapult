package fr.enimaloc.catapult.event.minecraft;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

public record MinecraftEnrollEvent(String username, String status, String minecraftName) implements ChannelUpdatedEvent {
}
