package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.DryRunStateDto;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The mock's member dry run: who is in it and since when, answering like catapult-api (putting a member already in
 * it is refused, taking one out who is not is a no-op). Nothing is held back for real: the mock changes no channel.
 */
class MockDryRunStore {
    private final Clock clock;
    private final Map<UUID, Instant> since = new HashMap<>();

    MockDryRunStore(Clock clock, UUID seededMember) {
        this.clock = clock;
        since.put(seededMember, clock.instant().minus(1, ChronoUnit.HOURS));
    }

    synchronized boolean active(UUID member) {
        return since.containsKey(member);
    }

    synchronized DryRunStateDto state(UUID member) {
        Instant at = since.get(member);
        return at == null ? DryRunStateDto.OFF : new DryRunStateDto(true, at);
    }

    /** 200 once on, 409 when already on. */
    synchronized int enable(UUID member) {
        return since.putIfAbsent(member, clock.instant()) == null ? 200 : 409;
    }

    synchronized int disable(UUID member) {
        since.remove(member);
        return 204;
    }
}
