package fr.enimaloc.catapult.getter.steam;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicBoolean;

class SteamRateLimiterTest {

    @Test
    void acquireBlocking_returnsTrue_whenPermitAvailable() {
        SteamRateLimiter limiter = new SteamRateLimiter(3, 2000, 5000, new SimpleMeterRegistry());
        assertThat(limiter.acquireBlocking("key")).isTrue();
    }

    @Test
    void acquireBlocking_returnsFalse_whenInterrupted() throws InterruptedException {
        SteamRateLimiter limiter = new SteamRateLimiter(0, 2000, 5000, new SimpleMeterRegistry());
        AtomicBoolean result = new AtomicBoolean(true);
        Thread t = new Thread(() -> result.set(limiter.acquireBlocking("key")));
        t.start();
        Thread.sleep(50);
        t.interrupt();
        t.join(1000);
        assertThat(result.get()).isFalse();
    }

    @Test
    void aKeysUsage_isItsPermitsTakenThisWindow_andItsCallsOfTheDay() {
        SteamRateLimiter limiter = new SteamRateLimiter(3, 50, 5000, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        limiter.setDailyLimit(1000);
        assertThat(limiter.usage("K")).isEqualTo(new fr.enimaloc.catapult.common.dto.admin.KeyUsageDto(
                0, 3, 5, null, "DAY", 0, 1000L, null));

        assertThat(limiter.acquire("K")).isTrue();
        assertThat(limiter.acquireBlocking("K")).isTrue();

        var usage = limiter.usage("K");
        assertThat(usage.windowCalls()).isEqualTo(2);
        assertThat(usage.periodCalls()).isEqualTo(2);
    }
}
