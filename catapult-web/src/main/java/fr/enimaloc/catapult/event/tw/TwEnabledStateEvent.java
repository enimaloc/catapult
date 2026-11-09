package fr.enimaloc.catapult.event.tw;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

public record TwEnabledStateEvent(String username, String bindingId, boolean enabled) implements ChannelUpdatedEvent {
}
