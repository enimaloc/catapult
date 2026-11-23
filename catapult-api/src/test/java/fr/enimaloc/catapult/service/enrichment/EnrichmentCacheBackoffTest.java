package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.domain.enrichment.GameEnrichmentCacheEntry;
import fr.enimaloc.catapult.repository.enrichment.GameEnrichmentCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        private volatile Instant now = Instant.parse("2026-11-23T12:00:00Z");

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
    private final Map<String, GameEnrichmentCacheEntry> saved = new ConcurrentHashMap<>();
    private final MovableClock clock = new MovableClock();
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private EnrichmentCache cache;

    @BeforeEach
    void setUp() {
        // a repository that remembers what is saved, like the real one: a late caller finds the answer cached
        when(repository.findBySourceAndLookupKey(anyString(), anyString())).thenAnswer(call ->
                Optional.ofNullable(saved.get(call.getArgument(0) + "|" + call.getArgument(1))));
        when(repository.save(any(GameEnrichmentCacheEntry.class))).thenAnswer(call -> {
            GameEnrichmentCacheEntry entry = call.getArgument(0);
            saved.put(entry.getSource() + "|" + entry.getLookupKey(), entry);
            return entry;
        });
        cache = new EnrichmentCache(repository, new EnrichmentProperties(), JsonMapper.builder().build(), clock);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
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

    /** A call whose answer does not matter, only whether it reached upstream. */
    private void tryGet(EnrichmentSource source, AtomicInteger calls, boolean fail) {
        try {
            get(source, calls, fail);
        } catch (EnrichmentUnavailableException expected) {
            // a failing key with nothing stale
        }
    }

    @Test
    void failure_isUnavailableNotAbsent_andTheSecondCallWithinBackoffDoesNotHitUpstream() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> get(EnrichmentSource.PROTONDB, calls, true))
                .isInstanceOfSatisfying(EnrichmentUnavailableException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(1)));
        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(() -> get(EnrichmentSource.PROTONDB, calls, false))
                .isInstanceOfSatisfying(EnrichmentUnavailableException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(1)));

        assertThat(calls).hasValue(1);
        verify(repository, never()).save(any());
    }

    @Test
    void failure_withAStaleValue_servesItInsteadOfBeingUnavailable() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(get(EnrichmentSource.PROTONDB, calls, false)).contains(new Payload("ok"));
        clock.advance(Duration.ofDays(30)); // stale: past its ttl

        assertThat(get(EnrichmentSource.PROTONDB, calls, true)).contains(new Payload("ok"));
        assertThat(get(EnrichmentSource.PROTONDB, calls, false)).contains(new Payload("ok"));

        assertThat(calls).hasValue(2); // the second one was within the backoff
    }

    @Test
    void backoff_expires_thenRetriesAndRecovers() {
        AtomicInteger calls = new AtomicInteger();
        tryGet(EnrichmentSource.STEAMDECK, calls, true);

        clock.advance(Duration.ofMinutes(1));

        assertThat(get(EnrichmentSource.STEAMDECK, calls, false)).contains(new Payload("ok"));
        assertThat(calls).hasValue(2);
        // the success forgot the failure and cached the value
        assertThat(get(EnrichmentSource.STEAMDECK, calls, true)).contains(new Payload("ok"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void backoff_doublesPerFailure_andIsCappedAtFifteenMinutes() {
        AtomicInteger calls = new AtomicInteger();
        tryGet(EnrichmentSource.PCGAMINGWIKI, calls, true);          // 1st failure: wait 1 min
        clock.advance(Duration.ofMinutes(1));
        tryGet(EnrichmentSource.PCGAMINGWIKI, calls, true);          // 2nd: wait 2 min
        clock.advance(Duration.ofMinutes(1));
        tryGet(EnrichmentSource.PCGAMINGWIKI, calls, true);          // still waiting: no call
        assertThat(calls).hasValue(2);
        clock.advance(Duration.ofMinutes(1));
        tryGet(EnrichmentSource.PCGAMINGWIKI, calls, true);          // 3rd: wait 4 min
        assertThat(calls).hasValue(3);

        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofMinutes(15));
            tryGet(EnrichmentSource.PCGAMINGWIKI, calls, true);
        }
        int before = calls.get();
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        tryGet(EnrichmentSource.PCGAMINGWIKI, calls, true);
        assertThat(calls).hasValue(before);
        clock.advance(Duration.ofSeconds(1));
        tryGet(EnrichmentSource.PCGAMINGWIKI, calls, true);
        assertThat(calls).hasValue(before + 1);
    }

    @Test
    void failureCount_startsOverAfterAQuietPeriodLongerThanTheCap() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(15));
            tryGet(EnrichmentSource.PROTONDB, calls, true);           // the wait is now at its cap
        }

        clock.advance(Duration.ofHours(2));                           // quiet for much longer than the cap
        assertThatThrownBy(() -> get(EnrichmentSource.PROTONDB, calls, true))
                .isInstanceOfSatisfying(EnrichmentUnavailableException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(1)));
    }

    @Test
    void backoff_isPerKey() {
        AtomicInteger calls = new AtomicInteger();
        tryGet(EnrichmentSource.PROTONDB, calls, true);

        Optional<Payload> other = cache.get(EnrichmentSource.PROTONDB, "steam:7", Payload.class,
                FetchBudget.UNLIMITED, () -> {
                    calls.incrementAndGet();
                    return Optional.of(new Payload("other"));
                });

        assertThat(other).contains(new Payload("other"));
        assertThat(calls).hasValue(2);
    }

    @Test
    void sourcesThatDoNotBackOff_retryAtOnce_andKeepServingEmpty() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(get(EnrichmentSource.HLTB, calls, true)).isEmpty();
        assertThat(get(EnrichmentSource.HLTB, calls, true)).isEmpty();

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
                        await(release);
                        return Optional.of(new Payload("shared"));
                    }), executor));
        }
        release.countDown();

        // whoever started late finds the saved answer: one upstream call either way
        for (CompletableFuture<Optional<Payload>> caller : callers) {
            assertThat(caller.get(5, TimeUnit.SECONDS)).contains(new Payload("shared"));
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void waitersSpendNoBudget_andShareAFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger budgetSpent = new AtomicInteger();
        FetchBudget counting = new FetchBudget() {
            @Override
            public boolean tryAcquire() {
                budgetSpent.incrementAndGet();
                return true;
            }
        };
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Optional<Payload>>> callers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            callers.add(CompletableFuture.supplyAsync(() -> cache.get(EnrichmentSource.STEAMDECK, KEY,
                    Payload.class, counting, () -> {
                        calls.incrementAndGet();
                        await(release);
                        throw new IllegalStateException("down");
                    }), executor));
        }
        release.countDown();

        // a late starter sees the recorded backoff: unavailable for everybody, one upstream call and one budget
        for (CompletableFuture<Optional<Payload>> caller : callers) {
            assertThatThrownBy(() -> caller.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(
                    EnrichmentUnavailableException.class);
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
