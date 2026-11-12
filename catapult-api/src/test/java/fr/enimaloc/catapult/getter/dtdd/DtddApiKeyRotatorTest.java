package fr.enimaloc.catapult.getter.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddApiKeyEntry;
import fr.enimaloc.catapult.repository.dtdd.DtddApiKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DtddApiKeyRotatorTest {

    @Mock DtddApiKeyRepository repository;
    DtddApiKeyRotator rotator;

    @BeforeEach
    void setUp() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new DtddApiKeyRotator(repository, new SimpleMeterRegistry());
    }

    @Test
    void nextKey_returnsEmpty_whenNoKeys() {
        assertThat(rotator.nextKey()).isEmpty();
    }

    @Test
    void nextKey_rotatesRoundRobin() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new DtddApiKeyEntry("KEY_A"),
            new DtddApiKeyEntry("KEY_B")
        ));
        rotator.refreshKeys();

        String first  = rotator.nextKey().orElseThrow();
        String second = rotator.nextKey().orElseThrow();
        String third  = rotator.nextKey().orElseThrow();

        assertThat(List.of(first, second)).containsExactlyInAnyOrder("KEY_A", "KEY_B");
        assertThat(third).isEqualTo(first);
    }

    @Test
    void nextKey_skipsBlockedKey() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new DtddApiKeyEntry("KEY_A"),
            new DtddApiKeyEntry("KEY_B")
        ));
        rotator.refreshKeys();

        rotator.onKeyRateLimited("KEY_A", 60);

        for (int i = 0; i < 5; i++) {
            assertThat(rotator.nextKey()).contains("KEY_B");
        }
    }

    @Test
    void nextKey_returnsLeastBlocked_whenAllBlocked() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new DtddApiKeyEntry("KEY_A")
        ));
        rotator.refreshKeys();

        rotator.onKeyRateLimited("KEY_A", 60);

        assertThat(rotator.nextKey()).contains("KEY_A");
    }

    @Test
    void isAllKeysBlocked_falseWhenSomeAvailable() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new DtddApiKeyEntry("KEY_A"),
            new DtddApiKeyEntry("KEY_B")
        ));
        rotator.refreshKeys();
        rotator.onKeyRateLimited("KEY_A", 60);
        assertThat(rotator.isAllKeysBlocked()).isFalse();
    }

    @Test
    void isAllKeysBlocked_trueWhenAllBlocked() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new DtddApiKeyEntry("KEY_A")
        ));
        rotator.refreshKeys();
        rotator.onKeyRateLimited("KEY_A", 60);
        assertThat(rotator.isAllKeysBlocked()).isTrue();
    }

    private void twoKeys() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new DtddApiKeyEntry("KEY_A"),
            new DtddApiKeyEntry("KEY_B")
        ));
        rotator.refreshKeys();
    }

    @Test
    void nextKeyAbove_skipsAKeyWhoseMonthIsDownToTheReserve() {
        twoKeys();
        rotator.onQuota("KEY_A", 20, 500);
        rotator.onQuota("KEY_B", 20, 501);

        for (int i = 0; i < 5; i++) {
            assertThat(rotator.nextKeyAbove(500)).contains("KEY_B");
        }
        // The detection still gets it
        assertThat(List.of(rotator.nextKey().orElseThrow(), rotator.nextKey().orElseThrow()))
            .containsExactlyInAnyOrder("KEY_A", "KEY_B");
    }

    @Test
    void nextKeyAbove_countsAnUnknownMonthAsEnough_butNeverGivesABlockedKey() {
        twoKeys();
        rotator.onKeyRateLimited("KEY_A", 60);

        assertThat(rotator.nextKeyAbove(500)).contains("KEY_B");
        rotator.onKeyRateLimited("KEY_B", 60);
        assertThat(rotator.nextKeyAbove(500)).isEmpty();
    }

    @Test
    void aMinuteSpent_restsTheKeyUntilTheNextOne() {
        twoKeys();
        long before = System.currentTimeMillis();

        rotator.onQuota("KEY_A", 0, 4000);

        assertThat(rotator.getKeyBlockedUntil().get("KEY_A")).isBetween(before + 59_000L, before + 61_000L);
        assertThat(rotator.getQuotas().get("KEY_A").remainingMonth()).isEqualTo(4000);
    }

    @Test
    void nextUsableAt_isEmptyOnceEveryMonthIsDownToTheReserve() {
        twoKeys();
        rotator.onKeyRateLimited("KEY_A", 30);
        rotator.onQuota("KEY_B", 10, 100);

        assertThat(rotator.keysAbove(500)).isEqualTo(1);
        assertThat(rotator.nextUsableAt(500)).hasValueSatisfying(at ->
            assertThat(at).isGreaterThan(System.currentTimeMillis() + 25_000L));

        rotator.onQuota("KEY_A", 10, 499);
        assertThat(rotator.nextUsableAt(500)).isEmpty();
        assertThat(rotator.keysAbove(500)).isZero();
    }

    @Test
    void aQuotaWithoutNumbers_isNotRemembered() {
        twoKeys();
        rotator.onQuota("KEY_A", null, null);
        assertThat(rotator.getQuotas()).isEmpty();
    }
}
