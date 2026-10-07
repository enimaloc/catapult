package fr.enimaloc.catapult.event;

public record GameChangedEvent(
        String username,
        String bindingId,
        String sourceType,
        String sourceName
) implements ChannelUpdatedEvent {
}
