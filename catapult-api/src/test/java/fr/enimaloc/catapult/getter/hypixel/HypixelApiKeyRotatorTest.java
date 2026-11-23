package fr.enimaloc.catapult.getter.hypixel;

import fr.enimaloc.catapult.domain.hypixel.HypixelApiKeyEntry;
import fr.enimaloc.catapult.repository.hypixel.HypixelApiKeyRepository;
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
class HypixelApiKeyRotatorTest {

    @Mock HypixelApiKeyRepository repository;
    HypixelApiKeyRotator rotator;
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
        rotator = new HypixelApiKeyRotator(repository, new SimpleMeterRegistry(), clock);
    }

    private void keys(String... keys) {
        when(repository.findByExclusiveFalse())
                .thenReturn(java.util.Arrays.stream(keys).map(HypixelApiKeyEntry::new).toList());
        rotator.refreshKeys();
    }

    @Test
    void anEmptyPool_givesNoKey() {
        assertThat(rotator.hasKeys()).isFalse();
        assertThat(rotator.nextKey()).isEmpty();
        assertThat(rotator.allBlocked()).isFalse();
    }

    @Test
    void theKeys_takeTheirTurn() {
        keys("A", "B");

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) seen.add(rotator.nextKey().orElseThrow());
        assertThat(seen).containsExactlyInAnyOrder("A", "B");
        assertThat(rotator.hasKeys()).isTrue();
    }

    @Test
    void aRefusedKey_isSkipped_untilItsTimeIsOver() {
        keys("A", "B");
        rotator.onKeyRejected("A", Duration.ofHours(1));

        for (int i = 0; i < 4; i++) assertThat(rotator.nextKey()).contains("B");
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        assertThat(rotator.availableKeyCount()).isEqualTo(1);
        assertThat(rotator.allBlocked()).isFalse();

        clock.advance(Duration.ofHours(1));
        assertThat(rotator.blockedKeyCount()).isZero();
    }

    @Test
    void whenAllAreBlocked_theOneFreeingFirstIsGiven() {
        keys("A", "B");
        rotator.onKeyRateLimited("A", Duration.ofSeconds(30));
        rotator.onKeyRateLimited("B", Duration.ofSeconds(10));

        assertThat(rotator.allBlocked()).isTrue();
        assertThat(rotator.nextKey()).contains("B");
        assertThat(rotator.getKeyBlockedUntil()).containsKeys("A", "B");
    }

    @Test
    void theMask_neverShowsTheKey() {
        String key = "12345678-abcd-4ef0-9abc-0123456789ab";
        assertThat(HypixelApiKeyRotator.mask(key)).isEqualTo("1234…89ab").doesNotContain("abcd-4ef0");
        assertThat(HypixelApiKeyRotator.mask("short")).isEqualTo("…");
    }

    @Test
    void usage_countsTheCallsSent() {
        rotator.onCall("A");
        rotator.onCall("A");

        assertThat(rotator.usage("A").windowCalls()).isEqualTo(2);
        assertThat(rotator.usage("B").windowCalls()).isZero();
    }

    @Test
    void anEntry_neverPrintsItsKey() {
        assertThat(new HypixelApiKeyEntry("12345678-abcd-4ef0-9abc-0123456789ab").toString())
                .doesNotContain("12345678");
    }
}
