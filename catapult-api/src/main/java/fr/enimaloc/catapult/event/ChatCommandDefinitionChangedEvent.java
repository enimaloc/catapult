package fr.enimaloc.catapult.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/** Émis après création/édition/suppression d'une ChatCommandDefinition pour invalider les caches. */
@Getter
public class ChatCommandDefinitionChangedEvent extends ApplicationEvent {
    private final UUID userId;

    public ChatCommandDefinitionChangedEvent(Object source, UUID userId) {
        super(source);
        this.userId = userId;
    }
}
