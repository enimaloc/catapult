package fr.enimaloc.catapult.getter.faceit;

import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import fr.enimaloc.catapult.repository.faceit.FaceitApiKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceitApiKeyRotatorTest {

    @Mock FaceitApiKeyRepository repository;
    FaceitApiKeyRotator rotator;
    MutableClock clock = new MutableClock();

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new FaceitApiKeyRotator(repository, new SimpleMeterRegistry(), clock);
    }

    private void keys(String... keys) {
        when(repository.findByExclusiveFalse()).thenReturn(java.util.Arrays.stream(keys)
                .map(FaceitApiKeyEntry::new).toList());
        rotator.refreshKeys();
    }

    @Test
    void anEmptyPool_givesNoKey() {
        assertThat(rotator.hasKeys()).isFalse();
        assertThat(rotator.nextKey()).isEmpty();
    }

    @Test
    void theKeys_areTakenInTurn() {
        keys("A", "B");

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) seen.add(rotator.nextKey().orElseThrow());
        assertThat(seen).containsExactlyInAnyOrder("A", "B");
        assertThat(rotator.availableKeyCount()).isEqualTo(2);
    }

    @Test
    void aRefusedKey_isSetAside_untilItsTimeIsOver() {
        keys("A", "B");

        rotator.onKeyRejected("A", Duration.ofMinutes(5));

        for (int i = 0; i < 4; i++) assertThat(rotator.nextKey()).contains("B");
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(6));
        assertThat(rotator.blockedKeyCount()).isZero();
    }

    @Test
    void whenEveryKeyIsSetAside_theOneThatFreesFirstIsGiven() {
        keys("A", "B");

        rotator.onKeyRateLimited("A", Duration.ofSeconds(30));
        rotator.onKeyRejected("B", Duration.ofMinutes(5));

        assertThat(rotator.nextKey()).contains("A");
        assertThat(rotator.availableKeyCount()).isZero();
    }

    @Test
    void theUsage_countsTheCallsSent() {
        keys("A");

        rotator.onCall("A");
        rotator.onCall("A");

        assertThat(rotator.usage("A").windowCalls()).isEqualTo(2);
        assertThat(rotator.getKeyBlockedUntil()).isEmpty();
    }
}
