package fr.enimaloc.catapult.event.steam;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/** Carries only {@code shared}, never the token itself — the SSE stream is not a place to echo secrets. */
public record SteamTokenSavedEvent(String username, boolean shared) implements ChannelUpdatedEvent {
}
