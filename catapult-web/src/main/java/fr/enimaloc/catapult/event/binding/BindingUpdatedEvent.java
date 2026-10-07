package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

public record BindingUpdatedEvent(String username, String bindingId, String twitchGameId, String twitchGameName,
                                  Set<String> ccls) implements ChannelUpdatedEvent {
}
