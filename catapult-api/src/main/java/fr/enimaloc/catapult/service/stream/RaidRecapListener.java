package fr.enimaloc.catapult.service.stream;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.stream.StreamSession;
import fr.enimaloc.catapult.event.ChannelRaidedOutEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * When a member raids another Catapult member, tells the raided one in Twitchat what the raider streamed during their
 * session so far (RAIDED_BY_MEMBER). Nothing goes out for a raid on a channel without a Catapult account, nor from a
 * raider without a session in progress or with nothing to list. Only the active site sends; a failure is logged, never
 * thrown to the EventSub thread that publishes the raid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RaidRecapListener {

    private final UserAccountRepository accounts;
    private final StreamSessionService sessions;
    private final SessionTimelineService timeline;
    private final TwitchatEventDispatcher dispatcher;
    private final SiteRole siteRole;

    /** After the other raid listeners (RaidRestoreGameService): the recap is only a courtesy. */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onRaid(ChannelRaidedOutEvent event) {
        if (!siteRole.isActive()) {
            return;
        }
        UserAccount raider = event.getUser();
        try {
            String targetId = event.getToBroadcasterId();
            if (targetId == null || targetId.isBlank()) {
                return;
            }
            Optional<UserAccount> target = accounts.findByTwitchId(targetId);
            if (target.isEmpty() || sessions.find(raider).filter(StreamSession::isOngoing).isEmpty()) {
                return;
            }
            SessionRecap recap = SessionRecap.of(timeline.currentSession(raider), event.getOccurredAt());
            if (recap.lines().isEmpty()) {
                return;
            }
            String raiderName = event.getFromBroadcasterName();
            dispatcher.onRaidedByMember(target.get(),
                    raiderName == null || raiderName.isBlank() ? raider.getTwitchUsername() : raiderName, recap);
        } catch (RuntimeException e) {
            log.warn("Could not send the session recap of user {} to the raided channel {}", raider.getId(),
                    event.getToBroadcasterId(), e);
        }
    }
}
