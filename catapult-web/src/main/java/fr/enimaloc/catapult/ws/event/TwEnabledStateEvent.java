package fr.enimaloc.catapult.ws.event;

public record TwEnabledStateEvent(String username, String bindingId, boolean enabled) implements ChannelUpdatedEvent {
}
