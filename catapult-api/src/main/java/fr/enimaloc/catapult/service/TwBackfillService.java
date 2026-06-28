package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.AppState;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.repository.AppStateRepository;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@ConditionalOnBooleanProperty(value = "tw.enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class TwBackfillService {

    private final AppStateRepository appStateRepo;
    private final GameBindingRepository bindingRepo;
    private final IgdbService igdbService;
    private final TwResolverService resolver;
    private final MeterRegistry meterRegistry;

    @Setter
    @Value("${tw.backfill.throttle-per-second:2}")
    int throttle;

    @Setter
    @Value("${tw.backfill.batch-size:50}")
    int batchSize;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (appStateRepo.findById("tw_backfill_completed").isPresent()) {
            log.debug("TW backfill already completed; skipping.");
            return;
        }
        log.info("TW backfill starting (throttle={}/s, batchSize={})", throttle, batchSize);
        Counts c = process(true);
        appStateRepo.save(new AppState(
            "tw_backfill_completed",
            "processed=%d skipped=%d failed=%d".formatted(c.processed, c.skipped, c.failed),
            Instant.now()));
        log.info("TW backfill done: processed={} skipped={} failed={}", c.processed, c.skipped, c.failed);
    }

    /**
     * Admin-triggered rebuild: re-resolve TW suggestions for every binding
     * whose streamer has not pinned them ({@code twOverride=false}), including
     * those that already carry a non-empty TW set. Used after the mapping
     * tables change so existing bindings pick up the new signals.
     */
    @Async
    public void rebuildAllNonOverridden() {
        log.info("TW rebuild starting (throttle={}/s, batchSize={})", throttle, batchSize);
        Counts c = process(false);
        log.info("TW rebuild done: processed={} skipped={} failed={}", c.processed, c.skipped, c.failed);
    }

    /**
     * Admin-triggered rebuild: re-resolve TW suggestions for every binding
     * whose streamer has not pinned them ({@code twOverride=false}), including
     * those that already carry a non-empty TW set. Used after the mapping
     * tables change so existing bindings pick up the new signals.
     */
    @Async
    public void rebuildAll() {
        log.info("TW rebuild starting (throttle={}/s, batchSize={})", throttle, batchSize);
        Counts c = process(true);
        log.info("TW rebuild done: processed={} skipped={} failed={}", c.processed, c.skipped, c.failed);
    }

    private static class Counts {
        int processed;
        int skipped;
        int failed;
    }

    /**
     * Shared loop body. {@code backfillOnly=true} restricts to the original
     * empty-TW candidates and applies the historical inner skip guard;
     * {@code false} processes every non-overridden binding (rebuild path).
     */
    private Counts process(boolean backfillOnly) {
        long sleepMs = 1000L / Math.max(throttle, 1);
        Counts c = new Counts();
        int page = 0;
        Page<GameBinding> batch;
        String mode = backfillOnly ? "backfill" : "rebuild";
        do {
            batch = backfillOnly
                ? bindingRepo.findCandidatesForTwBackfill(PageRequest.of(page++, batchSize))
                : bindingRepo.findCandidatesForTwRebuild(PageRequest.of(page++, batchSize));
            for (GameBinding b : batch) {
                if (b.isTwOverride() || (backfillOnly && !b.getTws().isEmpty())) {
                    c.skipped++;
                    meterRegistry.counter("catapult.tw." + mode + ".skipped").increment();
                    continue;
                }
                try {
                    Optional<String> igdbId = igdbService.resolveIgdbIdForBinding(b);
                    Set<Long> descIds = igdbId.map(igdbService::fetchDescriptorIds).orElse(Set.of());
                    String steamApp = b.getSourceType() == GameBinding.SourceType.STEAM ? b.getSourceId() : null;
                    Set<String> tws = resolver.suggest(new TwResolverService.SuggestInput(
                        igdbId.orElse(null), descIds, steamApp, b.getSourceName()));
                    log.debug("[TW] {} mapping for binding {} game '{}' (igdb={}): {}",
                        mode, b.getId(), b.getSourceName(), igdbId.orElse(null), tws);
                    b.setTws(tws);
                    bindingRepo.save(b);
                    c.processed++;
                    meterRegistry.counter("catapult.tw." + mode + ".processed").increment();
                } catch (Exception e) {
                    log.warn("TW {} failed for binding {}: {}", mode, b.getId(), e.getMessage());
                    c.failed++;
                    meterRegistry.counter("catapult.tw." + mode + ".failed").increment();
                }
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return c;
                }
            }
        } while (batch.hasNext());
        return c;
    }
}
