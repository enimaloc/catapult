package fr.enimaloc.catapult.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/** Emitted after a ChatCommandDefinition is created, edited or deleted, to invalidate the caches. */
@Getter
public class ChatCommandDefinitionChangedEvent extends ApplicationEvent {
    private final UUID userId;

    public ChatCommandDefinitionChangedEvent(Object source, UUID userId) {
        super(source);
        this.userId = userId;
    }
}
