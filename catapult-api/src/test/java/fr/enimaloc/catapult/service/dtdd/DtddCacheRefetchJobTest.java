package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddSearchCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The bulk refetch: the oldest entries first, under the rate limit, keeping the monthly reserve, cancellable. */
class DtddCacheRefetchJobTest {

    private static final int RESERVE = 500;
    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    /** A clock the sleeps move forward. */
    private static final class FakeClock extends Clock {
        final AtomicLong millis = new AtomicLong(T0.toEpochMilli());

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    }

    private final DtddApiClient client = mock(DtddApiClient.class);
    private final DtddApiClient bulk = mock(DtddApiClient.class);
    private final DtddApiKeyRotator rotator = mock(DtddApiKeyRotator.class);
    private final DtddTopicsCacheRepository topics = mock(DtddTopicsCacheRepository.class);
    private final DtddGameCacheRepository games = mock(DtddGameCacheRepository.class);
    private final DtddSearchCacheRepository searches = mock(DtddSearchCacheRepository.class);
    private final DtddCacheRefetcher refetcher = mock(DtddCacheRefetcher.class);
    private final FakeClock clock = new FakeClock();
    private final List<Long> sleeps = new ArrayList<>();
    private Runnable onSleep = () -> {};
    private Executor executor = Runnable::run;
    private DtddCacheRefetchJob job;

    @BeforeEach
    void setUp() {
        when(client.reserving(RESERVE)).thenReturn(bulk);
        when(rotator.nextUsableAt(RESERVE)).thenAnswer(inv -> Optional.of(clock.millis()));
        when(rotator.keysAbove(RESERVE)).thenReturn(1);
        when(rotator.availableKeyCount()).thenReturn(1);
        when(refetcher.refetch(eq(bulk), any(), any())).thenReturn(true);
        when(topics.findAllAges()).thenReturn(List.<Object[]>of(new Object[]{10L, T0.minusSeconds(100)}));
        when(games.findAllAges()).thenReturn(List.<Object[]>of(new Object[]{20L, T0.minusSeconds(300)}));
        when(searches.findAllAges()).thenReturn(List.<Object[]>of(new Object[]{"halo", T0.minusSeconds(200)}));
        job = job();
    }

    private DtddCacheRefetchJob job() {
        return new DtddCacheRefetchJob(Optional.of(client), Optional.of(rotator), topics, games, searches, refetcher,
                RESERVE, 30, task -> executor.execute(task), millis -> {
                    sleeps.add(millis);
                    clock.millis.addAndGet(millis);
                    onSleep.run();
                }, clock);
    }

    @Test
    void refetchesEverything_theOldestFirst_withTheReservingClient() {
        DtddRefetchJobDto started = job.start();

        assertThat(started.total()).isEqualTo(3);
        var order = inOrder(refetcher);
        order.verify(refetcher).refetch(bulk, DtddCacheKind.GAMES, "20");
        order.verify(refetcher).refetch(bulk, DtddCacheKind.SEARCHES, "halo");
        order.verify(refetcher).refetch(bulk, DtddCacheKind.TOPICS, "10");
        verify(client, never()).fetchTopics(anyLong());
        assertThat(job.status()).satisfies(s -> {
            assertThat(s.state()).isEqualTo("DONE");
            assertThat(s.done()).isEqualTo(3);
            assertThat(s.failed()).isZero();
            assertThat(s.finishedAt()).isNotNull();
        });
    }

    @Test
    void keepsUnderEachKeysRequestsPerMinute_sharedOverTheKeys() {
        job.start();
        // 30 a minute on one key: 2 s apart, in slices of a second at most
        assertThat(sleeps.stream().mapToLong(Long::longValue).sum()).isEqualTo(3 * 2_000L);
        assertThat(sleeps).allMatch(s -> s <= 1_000L);

        sleeps.clear();
        when(rotator.keysAbove(RESERVE)).thenReturn(2);
        job.start();
        assertThat(sleeps.stream().mapToLong(Long::longValue).sum()).isEqualTo(3 * 1_000L);
    }

    @Test
    void anEntryDtddGivesNothingFor_isCountedAndTheRestGoesOn() {
        when(refetcher.refetch(bulk, DtddCacheKind.SEARCHES, "halo")).thenReturn(false);
        when(refetcher.refetch(bulk, DtddCacheKind.TOPICS, "10")).thenThrow(new IllegalStateException("boom"));

        job.start();

        assertThat(job.status().done()).isEqualTo(3);
        assertThat(job.status().failed()).isEqualTo(2);
        assertThat(job.status().state()).isEqualTo("DONE");
    }

    @Test
    void waitsWhileEveryKeyRests() {
        long restUntil = clock.millis() + 5_500L;
        when(rotator.nextUsableAt(RESERVE)).thenAnswer(inv ->
                Optional.of(Math.max(restUntil, clock.millis())));
        List<Instant> waitingShown = new ArrayList<>();
        onSleep = () -> waitingShown.add(job.status().waitingUntil());

        job.start();

        assertThat(waitingShown.getFirst()).isEqualTo(Instant.ofEpochMilli(restUntil));
        verify(refetcher, times(3)).refetch(eq(bulk), any(), any());
        assertThat(job.status().state()).isEqualTo("DONE");
        assertThat(job.status().waitingUntil()).isNull();
    }

    @Test
    void stopsOnceEveryKeyIsDownToTheMonthlyReserve() {
        when(rotator.nextUsableAt(RESERVE)).thenReturn(Optional.of(clock.millis()), Optional.empty());

        job.start();

        verify(refetcher, times(1)).refetch(eq(bulk), any(), any());
        assertThat(job.status().state()).isEqualTo("STOPPED");
        assertThat(job.status().stopReason()).isEqualTo("QUOTA");
        assertThat(job.status().done()).isEqualTo(1);
    }

    @Test
    void stopsWithoutAnyKey() {
        when(rotator.nextUsableAt(RESERVE)).thenReturn(Optional.empty());
        when(rotator.availableKeyCount()).thenReturn(0);

        job.start();

        assertThat(job.status().stopReason()).isEqualTo("NO_KEY");
        verifyNoInteractions(refetcher);
    }

    @Test
    void aCancel_endsItAfterTheCurrentEntry() {
        onSleep = () -> job.cancel();

        job.start();

        verify(refetcher, times(1)).refetch(eq(bulk), any(), any());
        assertThat(job.status().state()).isEqualTo("CANCELLED");
        // Back from its pause at once
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void oneAtATime_andAnotherOnceItEnded() {
        List<Runnable> queued = new ArrayList<>();
        executor = queued::add;

        job.start();
        assertThatThrownBy(job::start).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");

        queued.getFirst().run();
        assertThat(job.status().state()).isEqualTo("DONE");
        job.start();
        assertThat(queued).hasSize(2);
    }

    @Test
    void withoutDtdd_isUnavailable() {
        DtddCacheRefetchJob off = new DtddCacheRefetchJob(Optional.empty(), Optional.empty(), topics, games, searches,
                refetcher, RESERVE, 30, Runnable::run, millis -> {}, clock);

        assertThatThrownBy(off::start).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        assertThat(off.status()).isEqualTo(DtddRefetchJobDto.IDLE);
    }
}
