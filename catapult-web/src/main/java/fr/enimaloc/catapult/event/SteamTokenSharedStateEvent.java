package fr.enimaloc.catapult.event;

import org.springframework.context.ApplicationEvent;

public record SteamTokenSharedStateEvent(String username, boolean shared) implements ChannelUpdatedEvent {
}
