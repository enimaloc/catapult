package fr.enimaloc.catapult.getter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicBoolean;

class SteamRateLimiterTest {

    @Test
    void acquireBlocking_returnsTrue_whenPermitAvailable() {
        SteamRateLimiter limiter = new SteamRateLimiter(3, 2000, 5000);
        assertThat(limiter.acquireBlocking("key")).isTrue();
    }

    @Test
    void acquireBlocking_returnsFalse_whenInterrupted() throws InterruptedException {
        SteamRateLimiter limiter = new SteamRateLimiter(0, 2000, 5000);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread t = new Thread(() -> result.set(limiter.acquireBlocking("key")));
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertThat(result.get()).isFalse();
    }
}
