package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SteamApiKeyRotatorTest {

    @Mock SteamApiKeyRepository repository;
    @Mock SteamRateLimiter rateLimiter;

    SteamApiKeyRotator rotator;

    @BeforeEach
    void setUp() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of());
        rotator = new SteamApiKeyRotator(repository, rateLimiter, false);
    }

    @Test
    void nextKey_returnsEmpty_whenNoKeys() {
        assertThat(rotator.nextKey()).isEmpty();
    }

    @Test
    void nextKey_rotatesRoundRobin() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new SteamApiKeyEntry("KEY_A"),
            new SteamApiKeyEntry("KEY_B")
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
            new SteamApiKeyEntry("KEY_A"),
            new SteamApiKeyEntry("KEY_B")
        ));
        rotator.refreshKeys();

        rotator.onKeyRateLimited("KEY_A", 60);

        for (int i = 0; i < 5; i++) {
            assertThat(rotator.nextKey()).contains("KEY_B");
        }
    }

    @Test
    void nextKey_returnsFallback_whenAllBlocked() {
        when(repository.findByExclusiveFalse()).thenReturn(List.of(
            new SteamApiKeyEntry("KEY_A")
        ));
        rotator.refreshKeys();

        rotator.onKeyRateLimited("KEY_A", 60);

        assertThat(rotator.nextKey()).contains("KEY_A");
    }

    @Test
    void onKeyRateLimited_pausesRateLimiter_whenConservative() {
        rotator = new SteamApiKeyRotator(repository, rateLimiter, true);
        rotator.onKeyRateLimited("KEY_A", 30);
        verify(rateLimiter).onRateLimitResponse(30);
    }

    @Test
    void onKeyRateLimited_doesNotPauseRateLimiter_whenNotConservative() {
        rotator.onKeyRateLimited("KEY_A", 30);
        verify(rateLimiter, never()).onRateLimitResponse(anyInt());
    }
}
