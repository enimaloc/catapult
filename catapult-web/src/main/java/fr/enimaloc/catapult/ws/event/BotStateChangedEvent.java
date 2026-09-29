package fr.enimaloc.catapult.ws.event;

public record BotStateChangedEvent(String username, boolean state) implements ChannelUpdatedEvent {
}
