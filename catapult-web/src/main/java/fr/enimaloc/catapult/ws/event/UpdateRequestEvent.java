package fr.enimaloc.catapult.ws.event;

public record UpdateRequestEvent(String username) implements ChannelUpdatedEvent {
}
