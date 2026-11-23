package fr.enimaloc.catapult.getter.riot;

import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import fr.enimaloc.catapult.repository.riot.RiotApiKeyRepository;
import fr.enimaloc.catapult.service.enrichment.riot.RiotPlatform.Cluster;
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
class RiotApiKeyRotatorTest {

    @Mock RiotApiKeyRepository repository;
    RiotApiKeyRotator rotator;
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
        rotator = new RiotApiKeyRotator(repository, new SimpleMeterRegistry(), clock);
    }

    private void keys(RiotApiKeyEntry... entries) {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(entries));
        rotator.refreshKeys();
    }

    @Test
    void anEmptyPool_servesNoCluster() {
        assertThat(rotator.hasKeys()).isFalse();
        assertThat(rotator.nextKey(Cluster.EUROPE)).isEmpty();
        assertThat(rotator.serves(Cluster.EUROPE)).isFalse();
    }

    @Test
    void anUnscopedKey_servesEveryCluster_aScopedOneOnlyItsOwn() {
        keys(new RiotApiKeyEntry("ALL", null), new RiotApiKeyEntry("EU", "europe"));

        Set<String> europe = new HashSet<>();
        for (int i = 0; i < 4; i++) europe.add(rotator.nextKey(Cluster.EUROPE).orElseThrow());
        assertThat(europe).containsExactlyInAnyOrder("ALL", "EU");
        for (int i = 0; i < 4; i++) assertThat(rotator.nextKey(Cluster.AMERICAS)).contains("ALL");
    }

    @Test
    void aKeyScopedElsewhere_leavesTheClusterUnserved() {
        keys(new RiotApiKeyEntry("EU", "europe"));

        assertThat(rotator.hasKeys()).isTrue();
        assertThat(rotator.serves(Cluster.ASIA)).isFalse();
        assertThat(rotator.nextKey(Cluster.ASIA)).isEmpty();
    }

    @Test
    void anUnknownScope_isTakenForEveryCluster() {
        keys(new RiotApiKeyEntry("ODD", "mars"));

        assertThat(rotator.nextKey(Cluster.SEA)).contains("ODD");
    }

    @Test
    void aRefusedKey_isSkipped_untilItsTimeIsOver() {
        keys(new RiotApiKeyEntry("A", null), new RiotApiKeyEntry("B", null));
        rotator.onKeyRejected("A", Duration.ofHours(1));

        for (int i = 0; i < 4; i++) assertThat(rotator.nextKey(Cluster.EUROPE)).contains("B");
        assertThat(rotator.blocked("A")).isTrue();
        assertThat(rotator.blocked("B")).isFalse();
        assertThat(rotator.blockedKeyCount()).isEqualTo(1);
        assertThat(rotator.availableKeyCount()).isEqualTo(1);

        clock.advance(Duration.ofHours(1));
        assertThat(rotator.blockedKeyCount()).isZero();
    }

    @Test
    void whenAllAreBlocked_theOneFreeingFirstIsGiven() {
        keys(new RiotApiKeyEntry("A", null), new RiotApiKeyEntry("B", null));
        rotator.onKeyRateLimited("A", Duration.ofSeconds(30));
        rotator.onKeyRateLimited("B", Duration.ofSeconds(10));

        assertThat(rotator.nextKey(Cluster.EUROPE)).contains("B");
        assertThat(rotator.getKeyBlockedUntil()).containsKeys("A", "B");
    }

    @Test
    void theMask_neverShowsTheKey() {
        String key = "RGAPI-12345678-abcd-4ef0-9abc-0123456789ab";
        assertThat(RiotApiKeyRotator.mask(key)).isEqualTo("RGAP…89ab").doesNotContain("12345678");
        assertThat(RiotApiKeyRotator.mask("short")).isEqualTo("…");
    }

    @Test
    void usage_countsTheCallsSent() {
        rotator.onCall("A");
        rotator.onCall("A");

        assertThat(rotator.usage("A").windowCalls()).isEqualTo(2);
        assertThat(rotator.usage("B").windowCalls()).isZero();
    }
}
