package fr.enimaloc.catapult.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The broadcast a page opened later still needs: the maintenance announced by the last
 * {@code maintenance.scheduled} ({@code {startsAt, durationMinutes, message}}), until a
 * {@code maintenance.cancelled} names its {@code originalStartsAt} or its window ends. Other
 * broadcasts are momentary and not kept.
 */
@Slf4j
@Component
public class BroadcastState {
    static final String SCHEDULED = "maintenance.scheduled";
    static final String CANCELLED = "maintenance.cancelled";

    private record Maintenance(Map<String, Object> data, Instant startsAt, Instant endsAt) {}

    private final AtomicReference<Maintenance> maintenance = new AtomicReference<>();
    private final Clock clock;

    public BroadcastState() {
        this(Clock.systemUTC());
    }

    BroadcastState(Clock clock) {
        this.clock = clock;
    }

    /** A broadcast catapult-api sent; a malformed maintenance one is ignored. */
    public void accept(String name, Map<String, Object> data) {
        if (data == null) return;
        try {
            if (SCHEDULED.equals(name)) {
                Instant startsAt = Instant.parse((String) data.get("startsAt"));
                long minutes = ((Number) data.get("durationMinutes")).longValue();
                maintenance.set(new Maintenance(Collections.unmodifiableMap(new LinkedHashMap<>(data)), startsAt, startsAt.plus(Duration.ofMinutes(minutes))));
            } else if (CANCELLED.equals(name)) {
                Instant original = Instant.parse((String) data.get("originalStartsAt"));
                maintenance.updateAndGet(current -> current != null && current.startsAt().equals(original) ? null : current);
            }
        } catch (RuntimeException e) {
            log.warn("Ignored a malformed {} broadcast: {}", name, e.getMessage());
        }
    }

    /** The maintenance announced and not yet over; empty once cancelled or ended. */
    public Optional<Map<String, Object>> maintenance() {
        Maintenance current = maintenance.get();
        if (current == null || !clock.instant().isBefore(current.endsAt())) return Optional.empty();
        return Optional.of(current.data());
    }
}
