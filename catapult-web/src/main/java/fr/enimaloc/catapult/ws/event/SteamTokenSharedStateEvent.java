package fr.enimaloc.catapult.ws.event;

import org.springframework.context.ApplicationEvent;

public record SteamTokenSharedStateEvent(String username, boolean shared) implements ChannelUpdatedEvent {
}
