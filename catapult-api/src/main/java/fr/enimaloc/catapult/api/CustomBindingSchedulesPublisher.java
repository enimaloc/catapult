package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.event.CustomBindingSchedulesChangedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the owner's open dashboards their bindings' schedule slots once they changed (saved from another tab or
 * device, suspended or resumed with premium, a binding moved to another category), as the dashboard reads them:
 * a dashboard showing an older list would otherwise post it back over the new one. After commit, so the slots
 * read are the ones written; reads only, which the committed transaction's resources still serve.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomBindingSchedulesPublisher {

    private final UserAccountRepository users;
    private final CustomBindingViews views;
    private final ChannelEventPublisher channelEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSchedulesChanged(CustomBindingSchedulesChangedEvent event) {
        try {
            users.findById(event.userId()).ifPresent(user -> {
                CustomBindingsStateDto state = views.stateIfEnabled(user);
                if (state != null) {
                    channelEventPublisher.customBindingSchedulesUpdated(user.getId(), state);
                }
            });
        } catch (Exception e) {
            // The dashboards keep what they show; the next change or a reload brings them up to date
            log.warn("Custom binding schedules of user {} not published", event.userId(), e);
        }
    }
}
