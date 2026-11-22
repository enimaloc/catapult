package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The per-key failure backoff and the shared in-flight fetch of the sources that back off (ProtonDB, Deck, PCGW). */
class EnrichmentCacheBackoffTest {

    record Payload(String name) {
    }

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final String KEY = "steam:42";

    private final GameEnrichmentCacheRepository repository = mock(GameEnrichmentCacheRepository.class);
    private final MovableClock clock = new MovableClock();
    private EnrichmentCache cache;

    @BeforeEach
    void setUp() {
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenReturn(Optional.empty());
        cache = new EnrichmentCache(repository, new EnrichmentProperties(), JsonMapper.builder().build(), clock);
    }

    private Optional<Payload> get(EnrichmentSource source, AtomicInteger calls, boolean fail) {
        return cache.get(source, KEY, Payload.class, FetchBudget.UNLIMITED, () -> {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("upstream down");
            }
            return Optional.of(new Payload("ok"));
        });
    }

    @Test
    void failure_secondCallWithinBackoff_doesNotHitUpstream_andIsNotCachedAsAbsent() {
        AtomicInteger calls = new AtomicInteger();

        assertThat(get(EnrichmentSource.PROTONDB, calls, true)).isEmpty();
        clock.advance(Duration.ofSeconds(59));
        assertThat(get(EnrichmentSource.PROTONDB, calls, false)).isEmpty();

        assertThat(calls).hasValue(1);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void backoff_expires_thenRetriesAndRecovers() {
        AtomicInteger calls = new AtomicInteger();
        get(EnrichmentSource.STEAMDECK, calls, true);

        clock.advance(Duration.ofMinutes(1));

        assertThat(get(EnrichmentSource.STEAMDECK, calls, false)).contains(new Payload("ok"));
        assertThat(calls).hasValue(2);
        // The success forgot the failure: the next stale lookup is not held back.
        assertThat(get(EnrichmentSource.STEAMDECK, calls, false)).contains(new Payload("ok"));
        assertThat(calls).hasValue(3);
    }

    @Test
    void backoff_doublesPerFailure_andIsCappedAtFifteenMinutes() {
        AtomicInteger calls = new AtomicInteger();
        get(EnrichmentSource.PCGAMINGWIKI, calls, true);          // 1st failure: wait 1 min
        clock.advance(Duration.ofMinutes(1));
        get(EnrichmentSource.PCGAMINGWIKI, calls, true);          // 2nd: wait 2 min
        clock.advance(Duration.ofMinutes(1));
        get(EnrichmentSource.PCGAMINGWIKI, calls, true);          // still waiting: no call
        assertThat(calls).hasValue(2);
        clock.advance(Duration.ofMinutes(1));
        get(EnrichmentSource.PCGAMINGWIKI, calls, true);          // 3rd: wait 4 min
        assertThat(calls).hasValue(3);

        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofMinutes(15));
            get(EnrichmentSource.PCGAMINGWIKI, calls, true);
        }
        int before = calls.get();
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        get(EnrichmentSource.PCGAMINGWIKI, calls, true);
        assertThat(calls).hasValue(before);
        clock.advance(Duration.ofSeconds(1));
        get(EnrichmentSource.PCGAMINGWIKI, calls, true);
        assertThat(calls).hasValue(before + 1);
    }

    @Test
    void backoff_isPerKey() {
        AtomicInteger calls = new AtomicInteger();
        get(EnrichmentSource.PROTONDB, calls, true);

        Optional<Payload> other = cache.get(EnrichmentSource.PROTONDB, "steam:7", Payload.class,
                FetchBudget.UNLIMITED, () -> {
                    calls.incrementAndGet();
                    return Optional.of(new Payload("other"));
                });

        assertThat(other).contains(new Payload("other"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void sourcesThatDoNotBackOff_retryAtOnce() {
        AtomicInteger calls = new AtomicInteger();
        get(EnrichmentSource.HLTB, calls, true);
        get(EnrichmentSource.HLTB, calls, true);

        assertThat(calls).hasValue(2);
    }

    @Test
    void concurrentCallersOfOneKey_shareOneUpstreamCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Optional<Payload>>> callers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            callers.add(CompletableFuture.supplyAsync(() -> cache.get(EnrichmentSource.PROTONDB, KEY, Payload.class,
                    FetchBudget.UNLIMITED, () -> {
                        calls.incrementAndGet();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Optional.of(new Payload("shared"));
                    })));
        }
        Thread.sleep(300);
        release.countDown();

        for (CompletableFuture<Optional<Payload>> caller : callers) {
            assertThat(caller.get(5, TimeUnit.SECONDS)).contains(new Payload("shared"));
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void waitersSpendNoBudget_andAFailureIsSharedToo() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger budgetSpent = new AtomicInteger();
        FetchBudget counting = new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                budgetSpent.incrementAndGet();
                return true;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ZERO;
            }
        };
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Optional<Payload>>> callers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            callers.add(CompletableFuture.supplyAsync(() -> cache.get(EnrichmentSource.STEAMDECK, KEY,
                    Payload.class, counting, () -> {
                        calls.incrementAndGet();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new IllegalStateException("down");
                    })));
        }
        Thread.sleep(300);
        release.countDown();

        for (CompletableFuture<Optional<Payload>> caller : callers) {
            assertThat(caller.get(5, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(calls).hasValue(1);
        assertThat(budgetSpent).hasValue(1);
    }

    @Test
    void refusedBudget_stillThrowsAndLeavesNoBackoff() {
        FetchBudget refused = new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                return false;
            }

            @Override
            public Duration retryAfter() {
                return Duration.ofSeconds(3);
            }
        };
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> cache.get(EnrichmentSource.PROTONDB, KEY, Payload.class, refused,
                () -> Optional.of(new Payload("x")))).isInstanceOf(UpstreamBudgetExceededException.class);

        assertThat(get(EnrichmentSource.PROTONDB, calls, false)).contains(new Payload("ok"));
        assertThat(calls).hasValue(1);
    }
}
