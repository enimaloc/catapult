package fr.enimaloc.catapult.event;

import org.springframework.context.ApplicationEvent;

public record SteamTokenDeletedEvent(String username) implements ChannelUpdatedEvent {
}
