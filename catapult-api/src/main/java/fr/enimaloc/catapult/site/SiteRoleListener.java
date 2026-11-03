package fr.enimaloc.catapult.site;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Closes the Twitch intakes (EventSub and chat sockets) when this site goes on standby and reopens them when it
 * becomes active again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteRoleListener {

    private final UserAccountRepository userAccountRepository;
    private final TwitchEventSubService twitchEventSubService;
    private final TwitchChatService twitchChatService;
    private final GameStateService gameStateService;

    @EventListener
    public void onSiteRoleChanged(SiteRoleChangedEvent event) {
        if (event.isActive()) {
            log.info("Site is active — reopening the Twitch intakes");
            attempt("EventSub init", null, twitchEventSubService::init);
            for (UserAccount user : botUsers()) {
                attempt("chat connect", user, () -> twitchChatService.connect(user));
            }
        } else {
            log.info("Site is on standby — closing the Twitch intakes");
            for (UserAccount user : botUsers()) {
                attempt("EventSub disconnect", user, () -> twitchEventSubService.disconnect(user));
                attempt("chat disconnect", user, () -> twitchChatService.disconnect(user));
                attempt("game state clear", user, () -> gameStateService.clearState(user));
            }
        }
    }

    private List<UserAccount> botUsers() {
        try {
            return userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE);
        } catch (RuntimeException e) {
            log.warn("Site role change: could not list the bot users", e);
            return List.of();
        }
    }

    /** One failing step must not stop the others: a socket left open on a standby site is the split-brain. */
    private void attempt(String step, UserAccount user, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Site role change: {} failed{}", step, user == null ? "" : " for user " + user.getId(), e);
        }
    }
}
