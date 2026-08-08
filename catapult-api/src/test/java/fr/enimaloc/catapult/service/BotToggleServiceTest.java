package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotToggleServiceTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TwitchChatService twitchChatService;
    @Mock private EventSubService twitchEventSubService;
    @Mock private ChannelEventPublisher channelEventPublisher;
    @Mock private TwitchatNotifier twitchatNotifier;
    @InjectMocks private BotToggleService botToggleService;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setBotEnabled(true);
    }

    @Test
    void setBotEnabled_disabling_disconnectsBothAndPublishes() {
        botToggleService.setBotEnabled(user, false);

        assertThat(user.isBotEnabled()).isFalse();
        verify(userAccountRepository).save(user);
        verify(twitchChatService).disconnect(user);
        verify(twitchEventSubService).disconnect(user);
        verify(channelEventPublisher).botToggled(user.getId(), false);
        verify(twitchChatService, never()).connect(any());
        verify(twitchEventSubService, never()).connect(any());
    }

    @Test
    void setBotEnabled_enabling_connectsBothAndPublishes() {
        user.setBotEnabled(false);

        botToggleService.setBotEnabled(user, true);

        assertThat(user.isBotEnabled()).isTrue();
        verify(userAccountRepository).save(user);
        verify(twitchChatService).connect(user);
        verify(twitchEventSubService).connect(user);
        verify(channelEventPublisher).botToggled(user.getId(), true);
        verify(twitchChatService, never()).disconnect(any());
        verify(twitchEventSubService, never()).disconnect(any());
    }

    @Test
    void setBotEnabled_noChange_stillSavesButSkipsConnectDisconnectAndPublish() {
        botToggleService.setBotEnabled(user, true);

        verify(userAccountRepository).save(user);
        verifyNoInteractions(twitchChatService, twitchEventSubService, channelEventPublisher);
    }

    @Test
    void setBotEnabled_transition_notifiesTwitchat() {
        botToggleService.setBotEnabled(user, false);

        verify(twitchatNotifier).onBotToggled(user, false);
    }

    @Test
    void setBotEnabled_noChange_doesNotNotifyTwitchat() {
        botToggleService.setBotEnabled(user, true); // user already has botEnabled=true from @BeforeEach

        verifyNoInteractions(twitchatNotifier);
    }
}
