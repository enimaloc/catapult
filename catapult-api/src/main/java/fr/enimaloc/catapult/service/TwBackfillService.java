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
        long sleepMs = 1000L / Math.max(throttle, 1);
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        int page = 0;
        Page<GameBinding> batch;
        do {
            batch = bindingRepo.findCandidatesForTwBackfill(PageRequest.of(page++, batchSize));
            for (GameBinding b : batch) {
                if (b.isTwOverride() || !b.getTws().isEmpty()) {
                    skipped++;
                    meterRegistry.counter("catapult.tw.backfill.skipped").increment();
                    continue;
                }
                try {
                    Optional<String> igdbId = igdbService.resolveIgdbIdForBinding(b);
                    Set<Long> descIds = igdbId.map(igdbService::fetchDescriptorIds).orElse(Set.of());
                    String steamApp = b.getSourceType() == GameBinding.SourceType.STEAM ? b.getSourceId() : null;
                    Set<String> tws = resolver.suggest(new TwResolverService.SuggestInput(
                        igdbId.orElse(null), descIds, steamApp, b.getSourceName()));
                    b.setTws(tws);
                    bindingRepo.save(b);
                    processed++;
                    meterRegistry.counter("catapult.tw.backfill.processed").increment();
                } catch (Exception e) {
                    log.warn("TW backfill failed for binding {}: {}", b.getId(), e.getMessage());
                    failed++;
                    meterRegistry.counter("catapult.tw.backfill.failed").increment();
                }
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } while (batch.hasNext());
        appStateRepo.save(new AppState(
            "tw_backfill_completed",
            "processed=%d skipped=%d failed=%d".formatted(processed, skipped, failed),
            Instant.now()));
        log.info("TW backfill done: processed={} skipped={} failed={}", processed, skipped, failed);
    }
}
