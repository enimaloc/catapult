package fr.enimaloc.catapult.ws.event;

public record BindingIgnoredStateEvent(String username, String bindingId, boolean ignored) implements ChannelUpdatedEvent {
}
