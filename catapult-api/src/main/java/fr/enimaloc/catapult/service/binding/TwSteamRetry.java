package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.event.TwsMissedSteamEvent;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A binding whose TWs missed the Steam store's signals (rate limited) has them resolved again once the store may be
 * called — a moment after its Retry-After, never holding up the detection — then pushed to its dashboard. Once per
 * binding at a time; still limited by then, it is planned again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TwSteamRetry {

    /** After the limit, so the store is surely callable again. */
    static final Duration MARGIN = Duration.ofSeconds(2);

    private final BindingService bindingService;
    private final ChannelEventPublisher channelEvents;
    private final TaskScheduler scheduler;
    private final Set<UUID> planned = ConcurrentHashMap.newKeySet();

    /** Once the binding is saved (its transaction committed, or none). */
    @TransactionalEventListener(fallbackExecution = true)
    public void onMissedSteam(TwsMissedSteamEvent event) {
        if (!planned.add(event.bindingId())) return;
        scheduler.schedule(() -> retry(event.bindingId()), event.until().plus(MARGIN));
        log.debug("[TW] binding {} resolved again after the Steam store's limit ({})", event.bindingId(), event.until());
    }

    void retry(UUID bindingId) {
        planned.remove(bindingId);
        try {
            bindingService.retryTwsAfterSteamLimit(bindingId)
                    .ifPresent(binding -> channelEvents.bindingUpserted(binding.getUser().getId(), binding));
        } catch (RuntimeException e) {
            log.warn("[TW] binding {} not resolved again after the Steam store's limit: {}", bindingId, e.getMessage());
        }
    }
}
