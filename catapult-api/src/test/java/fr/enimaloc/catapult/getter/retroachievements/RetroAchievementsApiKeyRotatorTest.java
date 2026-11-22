package fr.enimaloc.catapult.getter.retroachievements;

import fr.enimaloc.catapult.domain.retroachievements.RetroAchievementsApiKeyEntry;
import fr.enimaloc.catapult.repository.retroachievements.RetroAchievementsApiKeyRepository;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetroAchievementsApiKeyRotatorTest {

    @Mock RetroAchievementsApiKeyRepository repository;
    RetroAchievementsApiKeyRotator rotator;
    MutableClock clock = new MutableClock();

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-22T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new RetroAchievementsApiKeyRotator(repository, new SimpleMeterRegistry(), clock);
    }

    private void twoKeys() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new RetroAchievementsApiKeyEntry("KEY_A"), new RetroAchievementsApiKeyEntry("KEY_B")));
        rotator.refreshKeys();
    }

    @Test
    void emptyPool_nextKeyEmpty() {
        assertThat(rotator.nextKey()).isEmpty();
        assertThat(rotator.availableKeyCount()).isZero();
        assertThat(rotator.hasKeys()).isFalse();
    }

    @Test
    void hasKeys_evenWhenAllBlocked() {
        twoKeys();
        rotator.onKeyRejected("KEY_A", Duration.ofMinutes(1));
        rotator.onKeyRejected("KEY_B", Duration.ofMinutes(1));

        assertThat(rotator.hasKeys()).isTrue();
    }

    @Test
    void nextKey_rotatesRoundRobin() {
        twoKeys();

        String first = rotator.nextKey().orElseThrow();
        String second = rotator.nextKey().orElseThrow();
        String third = rotator.nextKey().orElseThrow();

        assertThat(List.of(first, second)).containsExactlyInAnyOrder("KEY_A", "KEY_B");
        assertThat(third).isEqualTo(first);
    }

    @Test
    void nextKey_skipsBlockedKey() {
        twoKeys();

        rotator.onKeyRejected("KEY_A", Duration.ofSeconds(60));

        for (int i = 0; i < 5; i++) {
            assertThat(rotator.nextKey()).contains("KEY_B");
        }
        assertThat(rotator.availableKeyCount()).isEqualTo(1);
        assertThat(rotator.getKeyBlockedUntil()).containsKey("KEY_A");
    }

    @Test
    void nextKey_whenAllBlocked_givesTheOneFreeingFirst() {
        twoKeys();
        rotator.onKeyRejected("KEY_A", Duration.ofSeconds(600));
        rotator.onKeyRejected("KEY_B", Duration.ofSeconds(60));

        assertThat(rotator.nextKey()).contains("KEY_B");
        assertThat(rotator.availableKeyCount()).isZero();
    }

    @Test
    void rejectedKeyIsSetAsideForAnHour() {
        twoKeys();

        rotator.onKeyRejected("KEY_A", Duration.ofHours(1));

        for (int i = 0; i < 4; i++) assertThat(rotator.nextKey()).contains("KEY_B");
        clock.advance(Duration.ofMinutes(61));
        assertThat(List.of(rotator.nextKey().orElseThrow(), rotator.nextKey().orElseThrow()))
                .containsExactlyInAnyOrder("KEY_A", "KEY_B");
    }

    @Test
    void rateLimitedKeyIsBlockedForTheRetryAfter() {
        twoKeys();

        rotator.onKeyRateLimited("KEY_B", 30);

        assertThat(rotator.nextKey()).contains("KEY_A");
        clock.advance(Duration.ofSeconds(31));
        assertThat(rotator.availableKeyCount()).isEqualTo(2);
    }

    @Test
    void usage_countsTheCallsOfTheMinute() {
        twoKeys();
        rotator.onCall("KEY_A");
        rotator.onCall("KEY_A");

        var usage = rotator.usage("KEY_A");

        assertThat(usage.windowCalls()).isEqualTo(2);
        assertThat(usage.windowSeconds()).isEqualTo(60);
        assertThat(rotator.usage("KEY_B").windowCalls()).isZero();
    }
}
