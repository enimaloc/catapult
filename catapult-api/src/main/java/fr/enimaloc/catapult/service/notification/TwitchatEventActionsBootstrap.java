package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gives every account with an enabled Twitchat widget its event actions at startup (converting
 * its legacy presets), once: the others get theirs when they enable the widget. Idempotent
 * through TwitchatWidgetSettings#eventActionsInitialized, like the ChatCommand*Migration runners.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TwitchatEventActionsBootstrap implements CommandLineRunner {

    private final TwitchatWidgetSettingsRepository widgetSettingsRepository;
    private final TwitchatEventActionService eventActionService;

    @Override
    public void run(String... args) {
        List<UserAccount> pending = widgetSettingsRepository.findUsersAwaitingEventActions();
        int done = 0;
        for (UserAccount user : pending) {
            try {
                eventActionService.ensureInitialized(user);
                done++;
            } catch (RuntimeException e) {
                log.warn("Twitchat event actions initialization failed for {}: {}", user.getId(), e.getMessage(), e);
            }
        }
        if (!pending.isEmpty()) {
            log.info("Twitchat event actions initialized for {}/{} account(s)", done, pending.size());
        }
    }
}
