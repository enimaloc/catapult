package fr.enimaloc.catapult.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ConfigOverrideAppliedEvent extends ApplicationEvent {
    private final String key;
    private final String newValue;

    public ConfigOverrideAppliedEvent(Object source, String key, String newValue) {
        super(source);
        this.key = key;
        this.newValue = newValue;
    }
}
