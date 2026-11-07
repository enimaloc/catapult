package fr.enimaloc.catapult.event;

public record ChannelLiveStateEvent(
        String username,
        boolean state
) implements ChannelUpdatedEvent {
}
