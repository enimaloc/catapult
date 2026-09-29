package fr.enimaloc.catapult.ws.event;

public record MinecraftSyncEvent(String username, String status, String minecraftName) implements ChannelUpdatedEvent {
}
