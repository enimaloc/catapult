package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Compares what Twitch says is live with the last stream remembered for the member, at every EventSub (re)connection,
 * so a start or end missed while this site was on standby is handled once, and one the other site already handled is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamCatchUp {

    /** The stream Twitch reports as live. */
    public record LiveStream(String id, Instant startedAt) {}

    private final StreamSessionService streamSessionService;
    private final StreamStateService streamStateService;
    private final ApplicationEventPublisher eventPublisher;

    public void reconcile(UserAccount user, Optional<LiveStream> live) {
        Optional<StreamSession> session = streamSessionService.find(user);
        if (live.isPresent()) {
            reconcileLive(user, live.get(), session);
        } else if (session.isPresent() && session.get().isOngoing()) {
            log.info("Catching up the end of the stream of user {} missed while away", user.getId());
            streamStateService.setLive(user, false);
            streamSessionService.recordEnd(user, Instant.now());
            eventPublisher.publishEvent(new StreamOfflineEvent(this, user));
        } else {
            streamStateService.restoreLive(user, false);
        }
    }

    private void reconcileLive(UserAccount user, LiveStream stream, Optional<StreamSession> session) {
        if (session.isEmpty()) {
            // First run with the marker: remember the stream without announcing it
            streamSessionService.recordStart(user, stream.id(), stream.startedAt());
            streamStateService.restoreLive(user, true);
        } else if (session.get().isOngoing() && stream.id().equals(session.get().getStreamId())) {
            streamStateService.restoreLive(user, true);
        } else {
            log.info("Catching up the start of stream {} of user {} missed while away", stream.id(), user.getId());
            streamSessionService.recordStart(user, stream.id(), stream.startedAt());
            streamStateService.setLive(user, true);
            eventPublisher.publishEvent(new StreamOnlineEvent(this, user));
        }
    }
}
