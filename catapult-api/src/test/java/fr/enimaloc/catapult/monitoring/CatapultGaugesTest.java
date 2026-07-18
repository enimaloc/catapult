package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.IrcTwitchChatService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.SystemTwitchAccountService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatapultGaugesTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock StreamStateService streamStateService;
    @Mock ObjectProvider<EventSubTwitchChatService> eventSubChatProvider;
    @Mock ObjectProvider<IrcTwitchChatService> ircChatProvider;
    @Mock ChatCommandDefinitionRepository chatCommandDefinitionRepository;
    @Mock OAuthTokenRepository oAuthTokenRepository;
    @Mock ObjectProvider<IgdbService> igdbServiceProvider;
    @Mock SystemTwitchAccountService systemTwitchAccountService;
    @Mock EventSubTwitchChatService eventSubTwitchChatService;
    @Mock MinecraftServiceAccountRepository minecraftServiceAccountRepository;

    private CatapultGauges gauges() {
        return new CatapultGauges(userAccountRepository, streamStateService,
                eventSubChatProvider, ircChatProvider, chatCommandDefinitionRepository,
                oAuthTokenRepository, igdbServiceProvider, systemTwitchAccountService,
                minecraftServiceAccountRepository);
    }

    @Test
    void bindTo_registersActiveUsersGauge() {
        when(userAccountRepository.countByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE)).thenReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        gauges().bindTo(registry);

        assertThat(registry.get("catapult.users.active").gauge().value()).isEqualTo(5.0);
    }

    @Test
    void bindTo_registersLiveStreamsGauge() {
        when(streamStateService.countLive()).thenReturn(3L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        gauges().bindTo(registry);

        assertThat(registry.get("catapult.streams.live").gauge().value()).isEqualTo(3.0);
    }

    @Test
    void chat_connection_gauges_registered() {
        when(eventSubChatProvider.getIfAvailable()).thenReturn(eventSubTwitchChatService);
        when(eventSubTwitchChatService.connectionCount()).thenReturn(2);
        when(ircChatProvider.getIfAvailable()).thenReturn(null);
        when(chatCommandDefinitionRepository.count()).thenReturn(5L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        gauges().bindTo(registry);

        assertThat(registry.get("catapult.chat.connections").tag("transport", "eventsub").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("catapult.chat.connections").tag("transport", "irc").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("catapult.commands.registered").gauge().value()).isEqualTo(5.0);
    }

    @Test
    void oauth_token_gauges_registered() {
        when(oAuthTokenRepository.countByExpiresAtAfter(any())).thenReturn(7L);
        when(oAuthTokenRepository.countByExpiresAtBefore(any())).thenReturn(2L);
        when(oAuthTokenRepository.countByRefreshTokenIsNull()).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        gauges().bindTo(registry);

        assertThat(registry.get("catapult.tokens.oauth").tag("state", "valid").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("catapult.tokens.oauth").tag("state", "expired").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("catapult.tokens.oauth").tag("state", "no_refresh").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void token_expiry_gauges_registered() {
        when(igdbServiceProvider.getIfAvailable()).thenReturn(null);
        when(systemTwitchAccountService.tokenExpiry()).thenReturn(Optional.of(Instant.now().plusSeconds(600)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        gauges().bindTo(registry);

        assertThat(registry.get("catapult.token.expiry.seconds").tag("service", "igdb").gauge().value()).isNaN();
        assertThat(registry.get("catapult.token.expiry.seconds").tag("service", "twitch_bot").gauge().value())
                .isCloseTo(600.0, within(5.0));
    }

    @Test
    void minecraft_accounts_gauge_registered() {
        when(minecraftServiceAccountRepository.countByEnabledTrue()).thenReturn(4L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        gauges().bindTo(registry);

        assertThat(registry.get("catapult.minecraft.accounts.total").gauge().value()).isEqualTo(4.0);
    }
}
