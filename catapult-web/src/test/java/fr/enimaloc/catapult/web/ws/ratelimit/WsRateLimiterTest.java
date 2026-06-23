package fr.enimaloc.catapult.web.ws.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WsRateLimiterTest {

    @Test
    void search_bucket_allows_10_per_second_and_rejects_11th() {
        var limiter = new WsRateLimiter();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_SEARCH))
                    .as("call %d", i)
                    .isTrue();
        }
        assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_SEARCH))
                .as("11th call")
                .isFalse();
    }

    @Test
    void buckets_are_isolated_per_session() {
        var limiter = new WsRateLimiter();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_SEARCH)).isTrue();
        }
        assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_SEARCH)).isFalse();
        // Second session unaffected.
        assertThat(limiter.tryAcquire("s2", WsRateLimiter.BUCKET_SEARCH)).isTrue();
    }

    @Test
    void buckets_are_isolated_per_bucket_name() {
        var limiter = new WsRateLimiter();
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_SEARCH)).isTrue();
        }
        assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_SEARCH)).isFalse();
        // Global bucket has its own capacity.
        assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_GLOBAL)).isTrue();
    }

    @Test
    void cleanup_drops_buckets_for_session() {
        var limiter = new WsRateLimiter();
        limiter.tryAcquire("s1", WsRateLimiter.BUCKET_SEARCH);
        assertThat(limiter.trackedSessions()).isEqualTo(1);
        limiter.cleanup("s1");
        assertThat(limiter.trackedSessions()).isEqualTo(0);
    }

    @Test
    void null_session_id_always_passes() {
        var limiter = new WsRateLimiter();
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire(null, WsRateLimiter.BUCKET_SEARCH)).isTrue();
        }
    }

    @Test
    void global_bucket_allows_100_per_second() {
        var limiter = new WsRateLimiter();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_GLOBAL)).isTrue();
        }
        assertThat(limiter.tryAcquire("s1", WsRateLimiter.BUCKET_GLOBAL)).isFalse();
    }
}
