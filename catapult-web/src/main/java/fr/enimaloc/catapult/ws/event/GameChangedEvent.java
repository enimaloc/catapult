package fr.enimaloc.catapult.ws.event;

public record GameChangedEvent(
        String username,
        String sourceType,
        String sourceName
) implements ChannelUpdatedEvent {
}
