package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@Profile("mock")
@RequiredArgsConstructor
public class MockTwitchEventSubService implements EventSubService {

    private final StreamStateService streamStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final UserAccountRepository userAccountRepository;

    @Override
    public void connect(UserAccount user) {
        log.info("[Mock Twitch] connect() called for user {} — no-op in mock mode", user.getId());
    }

    @Override
    public void disconnect(UserAccount user) {
        log.info("[Mock Twitch] disconnect() called for user {} — no-op in mock mode", user.getId());
        streamStateService.clear(user);
    }

    public void setOnline(UserAccount user) {
        streamStateService.setLive(user, true);
        eventPublisher.publishEvent(new StreamOnlineEvent(this, user));
        log.info("[Mock Twitch] stream.online → user {}", user.getId());
    }

    public void setOffline(UserAccount user) {
        streamStateService.setLive(user, false);
        eventPublisher.publishEvent(new StreamOfflineEvent(this, user));
        log.info("[Mock Twitch] stream.offline → user {}", user.getId());
    }

    public List<UserAccount> getAllUsers() {
        return userAccountRepository.findAll();
    }
}
