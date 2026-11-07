package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;


public record CclStateEvent(String username, String bindingId, boolean enabled) implements ChannelUpdatedEvent {
}
