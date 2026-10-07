package fr.enimaloc.catapult.event;

import java.util.Set;

public record TwUpdatedEvent(String username, String bindingId, Set<String> tws) implements ChannelUpdatedEvent {
}
