package fr.enimaloc.catapult.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricsEventListener {

    private final MeterRegistry registry;

    @EventListener
    public void onGameDetected(GameDetectedEvent event) {
        Counter.builder("catapult.game.detected")
                .tag("source", event.getDetectedGame().getSourceType().name())
                .register(registry)
                .increment();
    }

    @EventListener
    public void onNoGameDetected(NoGameDetectedEvent event) {
        Counter.builder("catapult.game.no_detected")
                .register(registry)
                .increment();
    }

    @EventListener
    public void onStreamOnline(StreamOnlineEvent event) {
        Counter.builder("catapult.stream.online")
                .register(registry)
                .increment();
    }

    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        Counter.builder("catapult.stream.offline")
                .register(registry)
                .increment();
    }

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        Counter.builder("catapult.account.created")
                .register(registry)
                .increment();
    }

    @EventListener
    public void onExperimentActivated(ExperimentActivatedEvent event) {
        Counter.builder("catapult.experiment.activated")
                .tag("experiment", event.getExperimentKey())
                .register(registry)
                .increment();
    }
}
