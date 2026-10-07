package fr.enimaloc.catapult.event;

public record BindingIgnoredStateEvent(String username, String bindingId, boolean ignored) implements ChannelUpdatedEvent {
}
