package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.IrcTwitchChatService;
import fr.enimaloc.catapult.service.StreamStateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatapultGaugesTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock StreamStateService streamStateService;
    @Mock ObjectProvider<EventSubTwitchChatService> eventSubChatProvider;
    @Mock ObjectProvider<IrcTwitchChatService> ircChatProvider;
    @Mock ChatCommandDefinitionRepository chatCommandDefinitionRepository;
    @Mock EventSubTwitchChatService eventSubTwitchChatService;

    @Test
    void bindTo_registersActiveUsersGauge() {
        when(userAccountRepository.countByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CatapultGauges(userAccountRepository, streamStateService,
                eventSubChatProvider, ircChatProvider, chatCommandDefinitionRepository).bindTo(registry);

        assertThat(registry.get("catapult.users.active").gauge().value()).isEqualTo(5.0);
    }

    @Test
    void bindTo_registersLiveStreamsGauge() {
        when(streamStateService.countLive()).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CatapultGauges(userAccountRepository, streamStateService,
                eventSubChatProvider, ircChatProvider, chatCommandDefinitionRepository).bindTo(registry);

        assertThat(registry.get("catapult.streams.live").gauge().value()).isEqualTo(3.0);
    }

    @Test
    void chat_connection_gauges_registered() {
        when(eventSubChatProvider.getIfAvailable()).thenReturn(eventSubTwitchChatService);
        when(eventSubTwitchChatService.connectionCount()).thenReturn(2);
        when(ircChatProvider.getIfAvailable()).thenReturn(null);
        when(chatCommandDefinitionRepository.count()).thenReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CatapultGauges(userAccountRepository, streamStateService,
                eventSubChatProvider, ircChatProvider, chatCommandDefinitionRepository).bindTo(registry);

        assertThat(registry.get("catapult.chat.connections").tag("transport", "eventsub").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("catapult.chat.connections").tag("transport", "irc").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("catapult.commands.registered").gauge().value()).isEqualTo(5.0);
    }
}
