package fr.enimaloc.catapult.event;

public record BotStateChangedEvent(String username, boolean state) implements ChannelUpdatedEvent {
}
