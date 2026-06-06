package fr.enimaloc.catapult.getter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SteamRateLimiterTest {

    @Test
    void acquireBlocking_returnsTrue_whenPermitAvailable() {
        SteamRateLimiter limiter = new SteamRateLimiter(3, 2000, 5000);
        assertThat(limiter.acquireBlocking()).isTrue();
    }

    @Test
    void acquireBlocking_returnsFalse_whenInterrupted() throws InterruptedException {
        SteamRateLimiter limiter = new SteamRateLimiter(0, 2000, 5000);
        Thread t = new Thread(() -> assertThat(limiter.acquireBlocking()).isFalse());
        t.start();
        t.interrupt();
        t.join(1000);
    }
}
