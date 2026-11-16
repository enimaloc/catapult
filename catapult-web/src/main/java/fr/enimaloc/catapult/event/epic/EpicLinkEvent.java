package fr.enimaloc.catapult.event.epic;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/** The channel's Epic link as it now stands; {@code NONE} once gone, the names then being null. */
public record EpicLinkEvent(String username, String status, String displayName, String serviceAccountName)
        implements ChannelUpdatedEvent {
}
