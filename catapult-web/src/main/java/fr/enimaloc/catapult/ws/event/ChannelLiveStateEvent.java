package fr.enimaloc.catapult.ws.event;

public record ChannelLiveStateEvent(
        String username,
        boolean state
) implements ChannelUpdatedEvent {
}
