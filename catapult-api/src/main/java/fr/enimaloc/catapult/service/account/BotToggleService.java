package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.twitch.EventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single seam for flipping {@link UserAccount#isBotEnabled()} and keeping every
 * live per-user Twitch connection in sync with it. Every code path that changes
 * botEnabled — streamer self-toggle, admin toggle, account deletion/unlink
 * lifecycle — must go through here instead of calling
 * {@code user.setBotEnabled(...)} directly, so the connections never drift.
 * <p>
 * A disabled bot has no open stream-state subscription but keeps its chat socket open, listening only, so that
 * {@code @<bot> on} can be heard. Whoever must really close the chat (token dead, account deleted or unlinked)
 * disconnects it explicitly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotToggleService {

    private final UserAccountRepository userAccountRepository;
    private final TwitchChatService twitchChatService;
    private final EventSubService twitchEventSubService;
    private final ChannelEventPublisher channelEventPublisher;
    private final TwitchatEventDispatcher twitchatEventDispatcher;

    /**
     * Pauses the bot because its Twitch token is dead (the refresh, or the call retried after it, failed), in a
     * transaction of its own: the caller's may roll back (a Twitchat revert answers an error), and the pause must stay,
     * as its side effects (connections closed, dashboard and Twitchat told) do. Unlike a plain disable, the chat is
     * closed too: a dead token cannot listen.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pauseAfterATokenFailure(UserAccount user) {
        setBotEnabled(user, false);
        twitchChatService.disconnect(user);
    }

    @Transactional
    public void setBotEnabled(UserAccount user, boolean enabled) {
        boolean changed = user.isBotEnabled() != enabled;
        user.setBotEnabled(enabled);
        userAccountRepository.save(user);

        if (!changed) {
            return;
        }
        if (enabled) {
            // Normally still open (listening only); reconnecting a live socket would only drop events
            if (!twitchChatService.isConnected(user)) {
                twitchChatService.connect(user);
            }
            twitchEventSubService.connect(user);
        } else {
            twitchEventSubService.disconnect(user);
        }
        channelEventPublisher.botToggled(user.getId(), enabled);
        twitchatEventDispatcher.onBotToggled(user, enabled);
        log.info("botEnabled set to {} for user {}", enabled, user.getId());
    }
}
