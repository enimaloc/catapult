package fr.enimaloc.catapult.event;

import org.springframework.context.ApplicationEvent;

public class TwDefinitionsChangedEvent extends ApplicationEvent {
    public TwDefinitionsChangedEvent(Object source) {
        super(source);
    }
}
