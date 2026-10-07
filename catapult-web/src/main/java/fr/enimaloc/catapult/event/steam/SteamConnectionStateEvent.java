package fr.enimaloc.catapult.event.steam;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

public record SteamConnectionStateEvent(String username, boolean connected, boolean rateLimited, boolean offline, boolean privateProfile) implements ChannelUpdatedEvent {
}
