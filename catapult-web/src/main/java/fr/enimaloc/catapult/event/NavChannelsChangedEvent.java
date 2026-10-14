package fr.enimaloc.catapult.event;

/** The channels a session's navbar lists changed (NavEventStreamController has it reload them). */
public record NavChannelsChangedEvent(String sessionId) {
}
