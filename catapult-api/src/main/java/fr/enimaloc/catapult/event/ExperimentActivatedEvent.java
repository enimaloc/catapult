package fr.enimaloc.catapult.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ExperimentActivatedEvent extends ApplicationEvent {

    private final UUID experimentId;
    private final String experimentKey;

    public ExperimentActivatedEvent(Object source, UUID experimentId, String experimentKey) {
        super(source);
        this.experimentId = experimentId;
        this.experimentKey = experimentKey;
    }
}
