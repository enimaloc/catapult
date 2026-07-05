package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.DtddApiKeyEntry;
import fr.enimaloc.catapult.repository.DtddApiKeyRepository;
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
}
