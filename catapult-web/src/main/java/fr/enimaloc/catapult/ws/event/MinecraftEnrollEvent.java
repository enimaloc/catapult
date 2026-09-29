package fr.enimaloc.catapult.ws.event;

public record MinecraftEnrollEvent(String username, String status, String minecraftName) implements ChannelUpdatedEvent {
}
