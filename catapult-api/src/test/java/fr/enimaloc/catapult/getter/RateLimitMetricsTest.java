package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.repository.DtddApiKeyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void steamRateLimiter_counts_key_and_global_blocks() {
        SteamRateLimiter limiter = new SteamRateLimiter(3, 100, 5000, registry);

        limiter.onRateLimitResponse("key1", 1);
        limiter.blockAll(1);

        assertThat(registry.get("catapult.external.rate_limited")
                .tag("api", "steam").tag("scope", "key").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("catapult.external.rate_limited")
                .tag("api", "steam").tag("scope", "global").counter().count()).isEqualTo(1.0);
    }

    @Test
    void dtddRotator_counts_key_blocks() {
        DtddApiKeyRepository repo = mock(DtddApiKeyRepository.class);
        when(repo.findByExclusiveFalse()).thenReturn(List.of());
        DtddApiKeyRotator rotator = new DtddApiKeyRotator(repo, registry);

        rotator.onKeyRateLimited("abcdefghijkl", 1);

        assertThat(registry.get("catapult.external.rate_limited")
                .tag("api", "dtdd").tag("scope", "key").counter().count()).isEqualTo(1.0);
    }
}
