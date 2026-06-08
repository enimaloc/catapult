package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ChannelCategoryChangedEvent;
import fr.enimaloc.catapult.event.ChannelCclChangedEvent;
import fr.enimaloc.catapult.event.StreamOfflineEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.mock.twitch-eventsub", havingValue = "true")
@RequiredArgsConstructor
public class MockTwitchEventSubService implements EventSubService {

    private final StreamStateService streamStateService;
    private final ApplicationEventPublisher eventPublisher;

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

    public void setCategory(UserAccount user, String categoryId, String categoryName) {
        eventPublisher.publishEvent(new ChannelCategoryChangedEvent(this, user, categoryId, categoryName));
        log.info("[Mock Twitch] channel.update/category → user {} category={}", user.getId(), categoryId);
    }

    public void setCcl(UserAccount user, List<String> cclIds) {
        eventPublisher.publishEvent(new ChannelCclChangedEvent(this, user, cclIds));
        log.info("[Mock Twitch] channel.update/ccl → user {} ccls={}", user.getId(), cclIds);
    }

}
