package fr.enimaloc.catapult.event;

/** Reset always clears the binding's warning overrides back to the channel defaults (empty set, no override). */
public record TwResetEvent(String username, String bindingId) implements ChannelUpdatedEvent {
}
