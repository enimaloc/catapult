package fr.enimaloc.catapult.service.tw;

import fr.enimaloc.catapult.common.dto.admin.TwRebuildStatusDto;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.domain.config.AppState;
import fr.enimaloc.catapult.ratelimit.RateLimitPriority;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.repository.config.AppStateRepository;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@ConditionalOnBooleanProperty(value = "tw.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TwBackfillService {

    /** The recalculations: the start-up backfill, and the admin's three rebuilds. */
    public enum Mode { BACKFILL, NON_OVERRIDDEN, UNTOUCHED, FORCE_PINNED }

    /** How many times a binding waits for the Steam store's rate limit before it counts as failed. */
    static final int MAX_STEAM_WAITS = 10;
    /** Sleeps that long at most at once, so a cancel is seen soon. */
    private static final long SLEEP_SLICE_MS = 1_000L;

    /** Waits {@code millis}; interrupted, the recalculation ends as cancelled. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final AppStateRepository appStateRepo;
    private final GameBindingRepository bindingRepo;
    private final GameMappingRepository mappingRepo;
    private final IgdbService igdbService;
    private final TwResolverService resolver;
    private final MeterRegistry meterRegistry;

    @Setter
    @Value("${tw.backfill.throttle-per-second:2}")
    int throttle;

    @Setter
    @Value("${tw.backfill.batch-size:50}")
    int batchSize;

    @Setter(AccessLevel.PACKAGE)
    private Sleeper sleeper = Thread::sleep;
    @Setter(AccessLevel.PACKAGE)
    private Clock clock = Clock.systemUTC();

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<TwRebuildStatusDto> status = new AtomicReference<>(TwRebuildStatusDto.IDLE);
    private volatile boolean cancelled;

    public TwRebuildStatusDto status() {
        return status.get();
    }

    /** Asks the running recalculation to stop after its current binding; nothing when none runs. */
    public TwRebuildStatusDto cancel() {
        if (running.get()) cancelled = true;
        return status.get();
    }

    /**
     * Starts an admin rebuild in the background, its progress in {@link #status()}.
     *
     * @throws ResponseStatusException 409 while a recalculation runs already
     */
    public TwRebuildStatusDto start(Mode mode) {
        claim(mode);
        Thread.ofVirtual().name("tw-rebuild").start(() -> runClaimed(mode));
        return status.get();
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (appStateRepo.findById("tw_backfill_completed").isPresent()) {
            log.debug("TW backfill already completed; skipping.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("TW backfill not started: a recalculation runs already.");
            return;
        }
        begin(Mode.BACKFILL);
        Counts c = runClaimed(Mode.BACKFILL);
        appStateRepo.save(new AppState(
            "tw_backfill_completed",
            "processed=%d skipped=%d failed=%d".formatted(c.processed, c.skipped, c.failed),
            Instant.now()));
    }

    /**
     * Admin-triggered rebuild: re-resolve TW suggestions for every binding
     * whose streamer has not pinned them ({@code twOverride=false}), including
     * those that already carry a non-empty TW set. Used after the mapping
     * tables change so existing bindings pick up the new signals.
     */
    @Async
    public void rebuildAllNonOverridden() {
        claim(Mode.NON_OVERRIDDEN);
        runClaimed(Mode.NON_OVERRIDDEN);
    }

    /**
     * Admin-triggered rebuild: re-resolve TW suggestions for the bindings that
     * carry none yet and whose streamer has not pinned them, as the start-up backfill.
     */
    @Async
    public void rebuildAll() {
        claim(Mode.UNTOUCHED);
        runClaimed(Mode.UNTOUCHED);
    }

    /**
     * Admin-triggered rebuild: re-resolve TW suggestions for every eligible
     * binding, INCLUDING those a streamer has pinned manually
     * ({@code twOverride=true}). Overwrites pinned choices — only meant for
     * a deliberate, one-off catalog-wide re-tag (e.g. after a large keyword
     * seed), not routine use.
     */
    @Async
    public void forceRebuildAllIncludingPinned() {
        claim(Mode.FORCE_PINNED);
        runClaimed(Mode.FORCE_PINNED);
    }

    private static class Counts {
        int processed;
        int skipped;
        int failed;
    }

    /** Takes the one recalculation slot for {@code mode}, its status reset. */
    private void claim(Mode mode) {
        if (!running.compareAndSet(false, true)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A TW recalculation runs already");
        }
        try {
            begin(mode);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void begin(Mode mode) {
        cancelled = false;
        long total = candidates(mode, PageRequest.of(0, Math.max(batchSize, 1))).getTotalElements();
        status.set(new TwRebuildStatusDto("RUNNING", mode.name(), total, 0, 0, 0, clock.instant(), null, null));
        log.info("TW {} starting: {} binding(s) (throttle={}/s, batchSize={})", mode, total, throttle, batchSize);
    }

    /** Runs the recalculation the slot was claimed for, then frees it. */
    private Counts runClaimed(Mode mode) {
        Counts c = new Counts();
        String state = "DONE";
        try {
            if (!process(mode, c)) state = "CANCELLED";
        } catch (RuntimeException e) {
            log.warn("TW {} ended on an error: {}", mode, e.getMessage(), e);
        } finally {
            TwRebuildStatusDto current = status.get();
            status.set(new TwRebuildStatusDto(state, mode.name(), current.total(), c.processed, c.skipped, c.failed,
                    current.startedAt(), clock.instant(), null));
            running.set(false);
            log.info("TW {} {}: processed={} skipped={} failed={}", mode, state.toLowerCase(Locale.ROOT),
                    c.processed, c.skipped, c.failed);
        }
        return c;
    }

    private Page<GameBinding> candidates(Mode mode, Pageable page) {
        return switch (mode) {
            case BACKFILL, UNTOUCHED -> bindingRepo.findCandidatesForTwBackfill(page);
            case NON_OVERRIDDEN -> bindingRepo.findCandidatesForTwRebuild(page);
            case FORCE_PINNED -> bindingRepo.findAllCandidatesForTwForceRebuild(page);
        };
    }

    /**
     * The loop: every candidate of {@code mode}, the streamer's pinned TWs left alone but by {@code FORCE_PINNED},
     * an admin's TWs always. Returns {@code false} when cancelled (or interrupted).
     */
    private boolean process(Mode mode, Counts c) {
        long sleepMs = 1000L / Math.max(throttle, 1);
        boolean backfillOnly = mode == Mode.BACKFILL || mode == Mode.UNTOUCHED;
        String metric = switch (mode) {
            case BACKFILL, UNTOUCHED -> "backfill";
            case NON_OVERRIDDEN -> "rebuild";
            case FORCE_PINNED -> "force_all";
        };
        int page = 0;
        Page<GameBinding> batch;
        do {
            batch = candidates(mode, PageRequest.of(page++, batchSize));
            for (GameBinding b : batch) {
                if (cancelled) return false;
                boolean pinned = mode != Mode.FORCE_PINNED && b.isTwOverride();
                if (pinned || (backfillOnly && !b.getTws().isEmpty()) || hasAdminTws(b)) {
                    c.skipped++;
                    meterRegistry.counter("catapult.tw." + metric + ".skipped").increment();
                    progress(c, null);
                    continue;
                }
                if (!remap(b, metric, c, sleepMs)) {
                    return false;
                }
            }
        } while (batch.hasNext());
        return true;
    }

    /**
     * A MAPPED binding whose admin game mapping replaces the TWs carries the admin's choice (even none), not an
     * IGDB suggestion: no backfill or rebuild may overwrite it.
     */
    private boolean hasAdminTws(GameBinding b) {
        return b.getStatus() == GameBinding.Status.MAPPED
            && mappingRepo.findBySourceTypeAndSourceId(b.getSourceType(), b.getSourceId())
                .map(GameMapping::isTwsOverridden)
                .orElse(false);
    }

    /**
     * Re-resolves one binding's TW suggestions, records the outcome in {@code c}, then
     * throttles. Missing the Steam store's signals (rate limited), it waits for the limit's end and tries again,
     * never saving TWs without them. Returns {@code false} when cancelled or interrupted.
     */
    private boolean remap(GameBinding b, String metric, Counts c, long sleepMs) {
        try {
            for (int waits = 0; ; waits++) {
                AtomicReference<Instant> limited = new AtomicReference<>();
                // Bulk traffic: kept under the learned rate limits with a margin for live calls, waiting its turn
                RateLimitPriority.run(RateLimitPriority.BULK, () -> cancelled,
                        () -> DebugContext.runAs(b.getUser().getId(), () -> limited.set(resolveTws(b, metric, c))));
                if (limited.get() == null) break;
                if (waits >= MAX_STEAM_WAITS) {
                    log.warn("TW {} gave up on binding {}: the Steam store stayed rate limited", metric, b.getId());
                    c.failed++;
                    meterRegistry.counter("catapult.tw." + metric + ".failed").increment();
                    break;
                }
                progress(c, limited.get());
                if (!pause(Duration.between(clock.instant(), limited.get()).toMillis() + 1_000L)) return false;
            }
            progress(c, null);
            return pause(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Sleeps {@code millis} in slices; {@code false} once cancelled. */
    private boolean pause(long millis) throws InterruptedException {
        long left = millis;
        while (left > 0) {
            if (cancelled) return false;
            long slice = Math.min(left, SLEEP_SLICE_MS);
            sleeper.sleep(slice);
            left -= slice;
        }
        return !cancelled;
    }

    private void progress(Counts c, Instant waitingUntil) {
        TwRebuildStatusDto current = status.get();
        status.set(new TwRebuildStatusDto("RUNNING", current.mode(), current.total(), c.processed, c.skipped, c.failed,
                current.startedAt(), null, waitingUntil));
    }

    /**
     * Re-resolves one binding's TW suggestions as its owner and records the outcome in {@code c}; returns until when
     * the Steam store is rate limited when its signals were missed (nothing saved then), else null.
     */
    private Instant resolveTws(GameBinding b, String metric, Counts c) {
        try {
            Optional<String> igdbId = igdbService.resolveIgdbIdForBinding(b);
            Set<Long> descIds = igdbId.map(igdbService::fetchDescriptorIds).orElse(Set.of());
            String steamApp = b.getSourceType() == GameBinding.SourceType.STEAM ? b.getSourceId() : null;
            TwResolverService.Suggestion suggestion = resolver.suggestReporting(new TwResolverService.SuggestInput(
                igdbId.orElse(null), descIds, steamApp, b.getSourceName()));
            if (suggestion.steamLimitedUntil().isPresent()) return suggestion.steamLimitedUntil().get();
            Set<String> tws = suggestion.tws();
            log.debug("[TW] {} mapping for binding {} game '{}' (igdb={}): {}",
                metric, b.getId(), b.getSourceName(), igdbId.orElse(null), tws);
            b.setTws(tws);
            bindingRepo.save(b);
            c.processed++;
            meterRegistry.counter("catapult.tw." + metric + ".processed").increment();
        } catch (Exception e) {
            log.warn("TW {} failed for binding {}: {}", metric, b.getId(), e.getMessage());
            c.failed++;
            meterRegistry.counter("catapult.tw." + metric + ".failed").increment();
        }
        return null;
    }
}
