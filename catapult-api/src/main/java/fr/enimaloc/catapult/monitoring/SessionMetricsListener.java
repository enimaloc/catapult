package fr.enimaloc.catapult.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionCreatedEvent;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class SessionMetricsListener implements MeterBinder {

    private final AtomicLong activeSessions = new AtomicLong(0);

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("catapult.sessions.active", activeSessions, AtomicLong::get)
                .description("Nombre de sessions HTTP actives")
                .register(registry);
    }

    @EventListener
    public void onSessionCreated(HttpSessionCreatedEvent event) {
        activeSessions.incrementAndGet();
    }

    @EventListener
    public void onSessionDestroyed(HttpSessionDestroyedEvent event) {
        activeSessions.decrementAndGet();
    }
}
