package fr.enimaloc.catapult.event.tw;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import java.util.Set;

public record TwUpdatedEvent(String username, String bindingId, Set<String> tws) implements ChannelUpdatedEvent {
}
