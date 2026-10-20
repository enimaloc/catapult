package fr.enimaloc.catapult.event;

/** The DoesTheDogDie mapping of the channel's games changed: the dashboard's mapping section is stale. */
public record DtddMappingChangedEvent(String username) implements ChannelUpdatedEvent {
}
