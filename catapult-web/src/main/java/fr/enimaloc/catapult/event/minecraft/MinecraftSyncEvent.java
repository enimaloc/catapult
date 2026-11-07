package fr.enimaloc.catapult.event.minecraft;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

public record MinecraftSyncEvent(String username, String status, String minecraftName) implements ChannelUpdatedEvent {
}
