package fr.enimaloc.catapult.event.steam;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import org.springframework.context.ApplicationEvent;

public record SteamTokenDeletedEvent(String username) implements ChannelUpdatedEvent {
}
