package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.admin.DtddRefetchJobDto;
import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import fr.enimaloc.catapult.getter.dtdd.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddSearchCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The admin's bulk refetch of every cached DTDD entry, the oldest first, one at a time on this instance (its state
 * lives here: a restart ends it). It keeps to DTDD's rate limit: under each key's requests per minute
 * ({@code dtdd.refetch.per-minute}, shared over the keys it may use), waiting while every such key rests, and only
 * with keys that keep more than {@code dtdd.refetch.monthly-reserve} requests of their month — the detection's.
 * Once none does, it stops (QUOTA). It can be cancelled.
 */
@Slf4j
@Component
public class DtddCacheRefetchJob {

    /** Sleeps that long at most at once, so a cancel is seen soon. */
    private static final long SLEEP_SLICE_MS = 1_000L;

    /** Waits {@code millis}; interrupted, the job ends as cancelled. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private record Target(DtddCacheKind kind, String key, Instant at) {}

    private final Optional<DtddApiClient> client;
    private final Optional<DtddApiKeyRotator> rotator;
    private final DtddTopicsCacheRepository topicsRepo;
    private final DtddGameCacheRepository gameRepo;
    private final DtddSearchCacheRepository searchRepo;
    private final DtddCacheRefetcher refetcher;
    private final int monthlyReserve;
    private final int perMinute;
    private final Executor executor;
    private final Sleeper sleeper;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<DtddRefetchJobDto> status = new AtomicReference<>(DtddRefetchJobDto.IDLE);
    private volatile boolean cancelled;

    @Autowired
    public DtddCacheRefetchJob(Optional<DtddApiClient> client, Optional<DtddApiKeyRotator> rotator,
                               DtddTopicsCacheRepository topicsRepo, DtddGameCacheRepository gameRepo,
                               DtddSearchCacheRepository searchRepo, DtddCacheRefetcher refetcher,
                               @Value("${dtdd.refetch.monthly-reserve:500}") int monthlyReserve,
                               @Value("${dtdd.refetch.per-minute:28}") int perMinute) {
        this(client, rotator, topicsRepo, gameRepo, searchRepo, refetcher, monthlyReserve, perMinute,
                task -> Thread.ofVirtual().name("dtdd-refetch").start(task), Thread::sleep, Clock.systemUTC());
    }

    DtddCacheRefetchJob(Optional<DtddApiClient> client, Optional<DtddApiKeyRotator> rotator,
                        DtddTopicsCacheRepository topicsRepo, DtddGameCacheRepository gameRepo,
                        DtddSearchCacheRepository searchRepo, DtddCacheRefetcher refetcher, int monthlyReserve,
                        int perMinute, Executor executor, Sleeper sleeper, Clock clock) {
        this.client = client;
        this.rotator = rotator;
        this.topicsRepo = topicsRepo;
        this.gameRepo = gameRepo;
        this.searchRepo = searchRepo;
        this.refetcher = refetcher;
        this.monthlyReserve = monthlyReserve;
        this.perMinute = Math.max(1, perMinute);
        this.executor = executor;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    public int monthlyReserve() {
        return monthlyReserve;
    }

    public DtddRefetchJobDto status() {
        return status.get();
    }

    /**
     * Starts the refetch of every cached entry, the oldest first.
     *
     * @throws ResponseStatusException 503 while DTDD is off, 409 while one runs already
     */
    public DtddRefetchJobDto start() {
        if (client.isEmpty() || rotator.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DTDD is disabled");
        }
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A DTDD refetch is running already");
        }
        try {
            cancelled = false;
            List<Target> targets = targets();
            status.set(new DtddRefetchJobDto("RUNNING", targets.size(), 0, 0, null, clock.instant(), null, null));
            log.info("DTDD caches refetch started: {} entries, monthly reserve {} per key", targets.size(),
                    monthlyReserve);
            executor.execute(() -> run(targets));
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return status.get();
    }

    /** Asks the running refetch to stop after its current entry; nothing when none runs. */
    public DtddRefetchJobDto cancel() {
        if (running.get()) cancelled = true;
        return status.get();
    }

    private List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        for (Object[] row : topicsRepo.findAllAges()) {
            targets.add(new Target(DtddCacheKind.TOPICS, String.valueOf(row[0]), (Instant) row[1]));
        }
        for (Object[] row : gameRepo.findAllAges()) {
            targets.add(new Target(DtddCacheKind.GAMES, String.valueOf(row[0]), (Instant) row[1]));
        }
        for (Object[] row : searchRepo.findAllAges()) {
            targets.add(new Target(DtddCacheKind.SEARCHES, (String) row[0], (Instant) row[1]));
        }
        targets.sort(Comparator.comparing(Target::at, Comparator.nullsFirst(Comparator.naturalOrder())));
        return targets;
    }

    private void run(List<Target> targets) {
        DtddApiKeyRotator keys = rotator.orElseThrow();
        DtddApiClient bulk = client.orElseThrow().reserving(monthlyReserve);
        int done = 0;
        int failed = 0;
        try {
            for (Target target : targets) {
                // A key that keeps the reserve, once one is usable
                while (true) {
                    if (cancelled) {
                        finish("CANCELLED", null, done, failed);
                        return;
                    }
                    Optional<Long> usableAt = keys.nextUsableAt(monthlyReserve);
                    if (usableAt.isEmpty()) {
                        boolean noKey = keys.availableKeyCount() + keys.blockedKeyCount() == 0;
                        finish("STOPPED", noKey ? "NO_KEY" : "QUOTA", done, failed);
                        return;
                    }
                    long waitMs = usableAt.get() - clock.millis();
                    if (waitMs <= 0) break;
                    progress(done, failed, Instant.ofEpochMilli(usableAt.get()));
                    pause(waitMs);
                }
                boolean refetched;
                try {
                    refetched = refetcher.refetch(bulk, target.kind(), target.key());
                } catch (RuntimeException e) {
                    log.warn("DTDD refetch of {} {} failed: {}", target.kind(), target.key(), e.getMessage());
                    refetched = false;
                }
                done++;
                if (!refetched) failed++;
                progress(done, failed, null);
                // Under each key's requests per minute, shared over the keys it may use
                pause(60_000L / ((long) perMinute * Math.max(1, keys.keysAbove(monthlyReserve))));
            }
            finish("DONE", null, done, failed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish("CANCELLED", null, done, failed);
        } catch (RuntimeException e) {
            log.warn("DTDD caches refetch ended on an error: {}", e.getMessage(), e);
            finish("STOPPED", "ERROR", done, failed);
        } finally {
            // Whatever ended it, another one may start
            running.set(false);
        }
    }

    /** Sleeps {@code millis} in slices, back early once cancelled. */
    private void pause(long millis) throws InterruptedException {
        long left = millis;
        while (left > 0 && !cancelled) {
            long slice = Math.min(left, SLEEP_SLICE_MS);
            sleeper.sleep(slice);
            left -= slice;
        }
    }

    private void progress(int done, int failed, Instant waitingUntil) {
        DtddRefetchJobDto current = status.get();
        status.set(new DtddRefetchJobDto("RUNNING", current.total(), done, failed, null, current.startedAt(), null,
                waitingUntil));
    }

    private void finish(String state, String stopReason, int done, int failed) {
        DtddRefetchJobDto current = status.get();
        status.set(new DtddRefetchJobDto(state, current.total(), done, failed, stopReason, current.startedAt(),
                clock.instant(), null));
        log.info("DTDD caches refetch {}{}: {}/{} done, {} without an answer", state,
                stopReason == null ? "" : " (" + stopReason + ")", done, current.total(), failed);
        running.set(false);
    }
}
