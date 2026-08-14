package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single seam for flipping {@link UserAccount#isBotEnabled()} and keeping every
 * live per-user Twitch connection in sync with it. Every code path that changes
 * botEnabled — streamer self-toggle, admin toggle, account deletion/unlink
 * lifecycle — must go through here instead of calling
 * {@code user.setBotEnabled(...)} directly, so a disabled bot always means "no
 * open chat socket, no open stream-state subscription" and never drifts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotToggleService {

    private final UserAccountRepository userAccountRepository;
    private final TwitchChatService twitchChatService;
    private final EventSubService twitchEventSubService;
    private final ChannelEventPublisher channelEventPublisher;
    private final TwitchatNotifier twitchatNotifier;

    @Transactional
    public void setBotEnabled(UserAccount user, boolean enabled) {
        boolean changed = user.isBotEnabled() != enabled;
        user.setBotEnabled(enabled);
        userAccountRepository.save(user);

        if (!changed) {
            return;
        }
        if (enabled) {
            twitchChatService.connect(user);
            twitchEventSubService.connect(user);
        } else {
            twitchChatService.disconnect(user);
            twitchEventSubService.disconnect(user);
        }
        channelEventPublisher.botToggled(user.getId(), enabled);
        twitchatNotifier.onBotToggled(user, enabled);
        log.info("botEnabled set to {} for user {}", enabled, user.getId());
    }
}
