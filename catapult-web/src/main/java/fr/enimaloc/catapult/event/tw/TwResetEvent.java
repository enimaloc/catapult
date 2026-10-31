package fr.enimaloc.catapult.event.tw;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import java.util.Set;

/** A binding without warning overrides: {@code tws} are the ones it resolved to on its own. */
public record TwResetEvent(String username, String bindingId, Set<String> tws) implements ChannelUpdatedEvent {
}
