package fr.enimaloc.catapult.service.enrichment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps the upstream calls of a source per UTC day, in memory: the counter restarts when the date changes, so a
 * refused call may be retried after the next UTC midnight.
 */
@Component
public class DailyCap {

    private record Day(LocalDate date, int used) {
    }

    private final Clock clock;
    private final ConcurrentHashMap<EnrichmentSource, Day> days = new ConcurrentHashMap<>();

    @Autowired
    public DailyCap() {
        this(Clock.systemUTC());
    }

    DailyCap(Clock clock) {
        this.clock = clock;
    }

    /** A budget granting {@code perDay} calls to {@code source} each UTC day. */
    public FetchBudget budget(EnrichmentSource source, int perDay) {
        return new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return acquire(source, perDay);
            }

            @Override
            public Duration retryAfter() {
                return untilNextUtcMidnight();
            }
        };
    }

    /** The time left until the counters restart, at least one second. */
    private Duration untilNextUtcMidnight() {
        Clock utc = clock.withZone(ZoneOffset.UTC);
        Duration left = Duration.between(clock.instant(),
                LocalDate.now(utc).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        return left.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : left;
    }

    private boolean acquire(EnrichmentSource source, int perDay) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        boolean[] granted = {false};
        days.compute(source, (k, day) -> {
            int used = day == null || !day.date().equals(today) ? 0 : day.used();
            if (used >= perDay) {
                return new Day(today, used);
            }
            granted[0] = true;
            return new Day(today, used + 1);
        });
        return granted[0];
    }
}
