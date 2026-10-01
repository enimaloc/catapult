package fr.enimaloc.catapult.ws.event;

public record SteamConnectionStateEvent(String username, boolean connected, boolean rateLimited, boolean offline, boolean privateProfile) implements ChannelUpdatedEvent {
}
