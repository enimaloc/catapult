package fr.enimaloc.catapult.getter.itad;

import fr.enimaloc.catapult.domain.itad.ItadApiKeyEntry;
import fr.enimaloc.catapult.repository.itad.ItadApiKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItadApiKeyRotatorTest {

    @Mock ItadApiKeyRepository repository;
    ItadApiKeyRotator rotator;

    @BeforeEach
    void setUp() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new ItadApiKeyRotator(repository, new SimpleMeterRegistry());
    }

    private void twoKeys() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new ItadApiKeyEntry("KEY_A"), new ItadApiKeyEntry("KEY_B")));
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
        rotator.block("KEY_A", Duration.ofMinutes(1));
        rotator.block("KEY_B", Duration.ofMinutes(1));

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

        rotator.block("KEY_A", Duration.ofSeconds(60));

        for (int i = 0; i < 5; i++) {
            assertThat(rotator.nextKey()).contains("KEY_B");
        }
        assertThat(rotator.availableKeyCount()).isEqualTo(1);
        assertThat(rotator.getKeyBlockedUntil()).containsKey("KEY_A");
    }

    @Test
    void nextKey_whenAllBlocked_givesTheOneFreeingFirst() {
        twoKeys();
        rotator.block("KEY_A", Duration.ofSeconds(600));
        rotator.block("KEY_B", Duration.ofSeconds(60));

        assertThat(rotator.nextKey()).contains("KEY_B");
        assertThat(rotator.availableKeyCount()).isZero();
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
