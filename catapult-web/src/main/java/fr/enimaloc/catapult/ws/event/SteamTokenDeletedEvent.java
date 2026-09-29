package fr.enimaloc.catapult.ws.event;

import org.springframework.context.ApplicationEvent;

public record SteamTokenDeletedEvent(String username) implements ChannelUpdatedEvent {
}
