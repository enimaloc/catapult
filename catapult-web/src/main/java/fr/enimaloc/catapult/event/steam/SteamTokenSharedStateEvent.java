package fr.enimaloc.catapult.event.steam;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import org.springframework.context.ApplicationEvent;

public record SteamTokenSharedStateEvent(String username, boolean shared) implements ChannelUpdatedEvent {
}
