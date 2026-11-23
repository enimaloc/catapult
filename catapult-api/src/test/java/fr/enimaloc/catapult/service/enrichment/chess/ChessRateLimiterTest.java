package fr.enimaloc.catapult.service.enrichment.chess;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChessRateLimiterTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-23T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aSecondCallOnTheSameSiteWaitsForTheFirstInsteadOfBeingRefused() throws Exception {
        ChessRateLimiter limiter = new ChessRateLimiter(Duration.ofSeconds(5), Clock.systemUTC());
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> limiter.call(ChessSite.LICHESS, () -> {
            most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            inFirst.countDown();
            await(releaseFirst);
            inFlight.decrementAndGet();
            return "first";
        }));
        assertThat(inFirst.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> limiter.call(ChessSite.LICHESS, () -> {
            most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            inFlight.decrementAndGet();
            return "second";
        }));
        Thread.sleep(100);
        assertThat(second).isNotDone();

        releaseFirst.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo("second");
        assertThat(most.get()).isEqualTo(1);
    }

    @Test
    void aCallThatWouldWaitPastTheBoundIsRefused() throws Exception {
        ChessRateLimiter limiter = new ChessRateLimiter(Duration.ofMillis(50), Clock.systemUTC());
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> limiter.call(ChessSite.CHESSCOM, () -> {
            inFirst.countDown();
            await(releaseFirst);
            return "first";
        }));
        assertThat(inFirst.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> limiter.call(ChessSite.CHESSCOM, () -> "second"))
                .isInstanceOf(ChessUnavailableException.class);

        releaseFirst.countDown();
        first.get(2, TimeUnit.SECONDS);
    }

    @Test
    void theSitesDoNotWaitForEachOther() throws Exception {
        ChessRateLimiter limiter = new ChessRateLimiter(Duration.ofMillis(50), Clock.systemUTC());
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> limiter.call(ChessSite.CHESSCOM, () -> {
            inFirst.countDown();
            await(releaseFirst);
            return "first";
        }));
        assertThat(inFirst.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(limiter.call(ChessSite.LICHESS, () -> "lichess")).isEqualTo("lichess");

        releaseFirst.countDown();
        first.get(2, TimeUnit.SECONDS);
    }

    @Test
    void theSlotIsGivenBackWhenTheCallFails() {
        ChessRateLimiter limiter = new ChessRateLimiter(Duration.ofMillis(50), Clock.systemUTC());

        assertThatThrownBy(() -> limiter.call(ChessSite.LICHESS, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(limiter.call(ChessSite.LICHESS, () -> "again")).isEqualTo("again");
    }

    @Test
    void aCallerQueuedBehindA429IsRefusedNotSent() throws Exception {
        ChessRateLimiter limiter = new ChessRateLimiter(Duration.ofSeconds(5), Clock.systemUTC());
        CountDownLatch inFirst = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> limiter.call(ChessSite.LICHESS, () -> {
            inFirst.countDown();
            await(answered);
            // the 429 is seen, and the site paused, before the slot is given back
            limiter.pause(ChessSite.LICHESS, Duration.ofSeconds(60));
            return "429";
        }));
        assertThat(inFirst.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> queued = CompletableFuture.supplyAsync(() ->
                limiter.call(ChessSite.LICHESS, sent::incrementAndGet));
        Thread.sleep(100);

        answered.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("429");
        assertThatThrownBy(() -> queued.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(ChessUnavailableException.class);
        assertThat(sent.get()).isZero();
    }

    @Test
    void aPauseRefusesTheSiteUntilItEnds() {
        MutableClock clock = new MutableClock();
        ChessRateLimiter limiter = new ChessRateLimiter(Duration.ofMillis(50), clock);
        AtomicInteger sent = new AtomicInteger();

        limiter.pause(ChessSite.LICHESS, Duration.ofSeconds(60));

        assertThat(limiter.paused(ChessSite.LICHESS)).isTrue();
        assertThatThrownBy(() -> limiter.call(ChessSite.LICHESS, sent::incrementAndGet))
                .isInstanceOf(ChessUnavailableException.class);
        assertThat(limiter.call(ChessSite.CHESSCOM, sent::incrementAndGet)).isEqualTo(1);
        clock.now = clock.now.plusSeconds(61);
        assertThat(limiter.call(ChessSite.LICHESS, sent::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void aShorterPauseDoesNotShortenALongerOne() {
        MutableClock clock = new MutableClock();
        ChessRateLimiter limiter = new ChessRateLimiter(Duration.ofMillis(50), clock);

        limiter.pause(ChessSite.CHESSCOM, Duration.ofSeconds(120));
        limiter.pause(ChessSite.CHESSCOM, Duration.ofSeconds(10));
        clock.now = clock.now.plusSeconds(60);

        assertThat(limiter.paused(ChessSite.CHESSCOM)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
