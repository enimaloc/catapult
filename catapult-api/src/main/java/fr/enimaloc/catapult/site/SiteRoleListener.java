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
            twitchEventSubService.init();
            userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)
                .forEach(twitchChatService::connect);
        } else {
            log.info("Site is on standby — closing the Twitch intakes");
            for (UserAccount user : userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)) {
                twitchEventSubService.disconnect(user);
                twitchChatService.disconnect(user);
                gameStateService.clearState(user);
            }
        }
    }
}
