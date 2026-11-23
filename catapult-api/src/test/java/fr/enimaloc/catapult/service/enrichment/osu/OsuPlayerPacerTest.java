package fr.enimaloc.catapult.service.enrichment.osu;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsuPlayerPacerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-23T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final OsuPlayerPacer pacer = new OsuPlayerPacer(clock);
    private final UUID member = UUID.randomUUID();
    private final String key = "osu-profile|u:" + member + ":osu:42";

    @Test
    void aSecondReadWithinTheMinute_getsTheFirstAnswer_withoutAnotherCall() {
        AtomicInteger calls = new AtomicInteger();

        assertThat(pacer.<String>pace(key, () -> "me-" + calls.incrementAndGet())).isEqualTo("me-1");
        assertThat(pacer.<String>pace(key, () -> "me-" + calls.incrementAndGet())).isEqualTo("me-1");
        assertThat(calls).hasValue(1);

        now.set(now.get().plus(OsuPlayerPacer.PER_PLAYER).plusSeconds(1));
        assertThat(pacer.<String>pace(key, () -> "me-" + calls.incrementAndGet())).isEqualTo("me-2");
    }

    @Test
    void aFailedReadWithinTheMinute_isAnOutage_neverAnotherCall() {
        assertThatThrownBy(() -> pacer.pace(key, () -> {
            throw new OsuUnavailableException("down");
        })).isInstanceOf(OsuUnavailableException.class);

        assertThatThrownBy(() -> pacer.pace(key, () -> "never")).isInstanceOf(OsuUnavailableException.class)
                .hasMessageContaining("less than a minute");
    }

    @Test
    void aConcurrentFirstAccess_sharesTheReadUnderWay() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> pacer.pace(key, () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "me";
        }));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> pacer.pace(key, () -> "never"));
        Thread.sleep(50);
        assertThat(second).isNotDone();

        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("me");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("me");
    }

    @Test
    void forget_letsTheNextReadGoAtOnce_andFinishedReadsAreForgottenAfterTheirMinute() {
        pacer.pace(key, () -> "me");
        pacer.pace("osu-profile|u:" + UUID.randomUUID() + ":osu:7", () -> "other");

        pacer.forget(member);
        assertThat(pacer.<String>pace(key, () -> "again")).isEqualTo("again");

        now.set(now.get().plus(OsuPlayerPacer.PER_PLAYER).plusSeconds(1));
        pacer.pace("osu-recent|u:" + member + ":osu:42:recent:osu", () -> "x");
        assertThat(pacer.size()).isEqualTo(1);
    }
}
