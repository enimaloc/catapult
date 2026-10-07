package fr.enimaloc.catapult.event;

public record MinecraftEnrollEvent(String username, String status, String minecraftName) implements ChannelUpdatedEvent {
}
