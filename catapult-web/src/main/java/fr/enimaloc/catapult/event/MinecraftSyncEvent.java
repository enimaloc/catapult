package fr.enimaloc.catapult.event;

public record MinecraftSyncEvent(String username, String status, String minecraftName) implements ChannelUpdatedEvent {
}
