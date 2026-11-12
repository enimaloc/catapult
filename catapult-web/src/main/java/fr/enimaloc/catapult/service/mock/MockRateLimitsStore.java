package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitCorrectionDto;
import fr.enimaloc.catapult.common.dto.admin.LearnedRateLimitDto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The mock's learned rate limits: the Steam store's host with its routes (the page split off with a limit of its
 * own) and an Xbox host never limited. Corrected and reset like catapult-api: 400 for an invalid correction, 404 for
 * an unknown scope.
 */
class MockRateLimitsStore {

    private static final String STORE = "store.steampowered.com";
    private final List<LearnedRateLimitDto> limits = new ArrayList<>();

    MockRateLimitsStore(Clock clock) {
        Instant now = clock.instant();
        limits.add(new LearnedRateLimitDto("HOST " + STORE, STORE, null, null, null, false, 190, 300, false, 76_800,
                false, 4, now.minus(Duration.ofMinutes(42)), 23, null));
        limits.add(new LearnedRateLimitDto("GET " + STORE + "/api/appdetails", STORE, "GET", "/api/appdetails", "HOST",
                false, null, null, false, 60_000, false, 3, now.minus(Duration.ofMinutes(42)), 21, null));
        limits.add(new LearnedRateLimitDto("GET " + STORE + "/app/{id}", STORE, "GET", "/app/{id}", "ROUTE", false, 12,
                60, false, 30_000, false, 1, now.minus(Duration.ofMinutes(3)), 12, now.plusSeconds(25)));
        limits.add(new LearnedRateLimitDto("HOST titlehub.xboxlive.com", "titlehub.xboxlive.com", null, null, null,
                false, null, null, false, 60_000, false, 0, null, 2, null));
    }

    List<LearnedRateLimitDto> list() {
        return List.copyOf(limits);
    }

    int correct(String id, LearnedRateLimitCorrectionDto c) {
        int i = indexOf(id);
        if (i < 0) return 404;
        if (c == null || (c.limitCalls() == null) != (c.limitWindowSeconds() == null)
                || (c.limitCalls() != null && (c.limitCalls() < 1 || c.limitWindowSeconds() < 1))
                || (c.resumeSeconds() != null && c.resumeSeconds() < 1)) {
            return 400;
        }
        LearnedRateLimitDto d = limits.get(i);
        String named = c.scope() == null ? "" : c.scope().trim().toUpperCase(Locale.ROOT);
        boolean unpin = named.equals("LEARNED");
        String scope = named.isEmpty() || unpin || d.hostScope() ? d.scope() : named;
        if (scope != null && !scope.equals("HOST") && !scope.equals("ROUTE")) return 400;
        boolean pinned = !d.hostScope() && !unpin && (d.scopePinned() || !named.isEmpty());
        boolean limitSet = c.limitCalls() != null;
        boolean resumeSet = c.resumeSeconds() != null;
        limits.set(i, new LearnedRateLimitDto(d.id(), d.host(), d.method(), d.path(), scope,
                pinned,
                limitSet ? c.limitCalls() : d.limitCalls(), limitSet ? c.limitWindowSeconds() : d.limitWindowSeconds(),
                limitSet, resumeSet ? c.resumeSeconds() * 1000L : d.resumeMs(), resumeSet, d.rateLimitedCount(),
                d.lastRateLimitedAt(), d.usedCalls(), d.blockedUntil()));
        return 200;
    }

    int reset(String id) {
        int i = indexOf(id);
        if (i < 0) return 404;
        LearnedRateLimitDto d = limits.get(i);
        limits.set(i, new LearnedRateLimitDto(d.id(), d.host(), d.method(), d.path(), d.hostScope() ? null : "HOST", false,
                null, null, false, 60_000, false, 0, null, d.usedCalls(), null));
        return 200;
    }

    private int indexOf(String id) {
        for (int i = 0; i < limits.size(); i++) {
            if (limits.get(i).id().equals(id)) return i;
        }
        return -1;
    }
}
