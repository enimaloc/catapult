package fr.enimaloc.catapult.event;

public record TwEnabledStateEvent(String username, String bindingId, boolean enabled) implements ChannelUpdatedEvent {
}
