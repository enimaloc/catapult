package fr.enimaloc.catapult.event.steam;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;


public record SteamTokenSharedStateEvent(String username, boolean shared) implements ChannelUpdatedEvent {
}
