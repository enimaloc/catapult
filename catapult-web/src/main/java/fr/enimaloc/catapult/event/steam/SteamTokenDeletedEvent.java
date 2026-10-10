package fr.enimaloc.catapult.event.steam;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;


public record SteamTokenDeletedEvent(String username) implements ChannelUpdatedEvent {
}
