package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.MutableClock;
import fr.enimaloc.catapult.service.WellKnownContent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class WellKnownCacheTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
    private final WellKnownCache cache = new WellKnownCache(clock);
    private final AtomicInteger loads = new AtomicInteger();

    private WellKnownContent load(int status) {
        loads.incrementAndGet();
        return new WellKnownContent(status, null);
    }

    @Test
    void servesFromCacheBefore60sAndReloadsAfter() {
        cache.get(WELL_KNOWN, "a", () -> load(200));
        clock.now = clock.now.plusSeconds(59);
        cache.get(WELL_KNOWN, "a", () -> load(200));
        assertThat(loads).hasValue(1);
        clock.now = clock.now.plusSeconds(1);
        cache.get(WELL_KNOWN, "a", () -> load(200));
        assertThat(loads).hasValue(2);
    }

    @Test
    void anotherInstancesClear_emptiesThisOneToo() {
        fr.enimaloc.catapult.cluster.InstanceBus bus = fr.enimaloc.catapult.cluster.InstanceBus.local();
        WellKnownCache here = new WellKnownCache(clock, bus);
        WellKnownCache there = new WellKnownCache(clock, bus);
        here.get(WELL_KNOWN, "a", () -> load(200));

        there.clear();
        here.get(WELL_KNOWN, "a", () -> load(200));

        assertThat(loads).hasValue(2);
    }

    @Test
    void doesNotStoreFailures() {
        cache.get(WELL_KNOWN, "a", () -> load(502));
        cache.get(WELL_KNOWN, "a", () -> load(502));
        assertThat(loads).hasValue(2);
    }

    @Test
    void isBounded_andReclaimsExpiredEntries() {
        for (int i = 0; i < WellKnownCache.MAX_ENTRIES; i++) {
            String path = "p" + i;
            cache.get(WELL_KNOWN, path, () -> load(404));
        }
        loads.set(0);
        cache.get(WELL_KNOWN, "new", () -> load(404));
        cache.get(WELL_KNOWN, "new", () -> load(404));
        assertThat(loads).hasValue(2); // full of live entries: not stored

        clock.now = clock.now.plusSeconds(60);
        loads.set(0);
        cache.get(WELL_KNOWN, "new", () -> load(404));
        cache.get(WELL_KNOWN, "new", () -> load(404));
        assertThat(loads).hasValue(1); // expired entries dropped, now stored
    }

    @Test
    void doesNotKeepAnAnswerLoadedAcrossAClear() {
        cache.get(WELL_KNOWN, "a", () -> {
            cache.clear(); // an admin write lands while the answer is being loaded
            return load(200);
        });
        cache.get(WELL_KNOWN, "a", () -> load(200));
        assertThat(loads).hasValue(2);
    }
}
