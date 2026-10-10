package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

public record BindingIgnoredStateEvent(String username, String bindingId, boolean ignored) implements ChannelUpdatedEvent {
}
