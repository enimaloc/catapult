package fr.enimaloc.catapult.event.ankama;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/** The channel's Ankama link as it now stands; {@code NONE} once gone, the tags then being null. */
public record AnkamaLinkEvent(String username, String status, String nameTag, String serviceAccountTag)
        implements ChannelUpdatedEvent {
}
