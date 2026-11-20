package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.service.debug.DebugRecording;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import fr.enimaloc.catapult.service.twitch.IrcTwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import fr.enimaloc.catapult.service.twitch.TwitchChatRateLimiter;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchHelixChannelClient;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import fr.enimaloc.catapult.simulation.twitch.ChannelUpdateEcho;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchCategoryService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchChatService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchService;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchTokenService;
import fr.enimaloc.catapult.site.SiteRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dry-run gate must be the outermost {@link TwitchService} / {@link TwitchChatService} every consumer gets, with
 * the simulation off (the real clients) and on (the simulated decorators, ahead of the simulation), while the beans
 * injected by their concrete class keep resolving and keep their own Spring callbacks.
 */
class DryRunWiringTest {

    /** What a consumer of the two interfaces, or of the concrete clients, is handed. */
    record Consumer(TwitchService twitch, TwitchChatService chat, ObjectProvider<TwitchServiceImpl> realTwitch,
                    ObjectProvider<IrcTwitchChatService> irc) {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withAllowCircularReferences(false)
            .withPropertyValues("app.chat.provider=irc")
            .withBean(DryRunTwitchWrapping.class)
            .withBean(DryRunGate.class, DryRunWiringTest::holdingGate)
            .withBean(Consumer.class)
            // the real clients
            .withBean(TwitchServiceImpl.class)
            .withBean(IrcTwitchChatService.class)
            .withBean(OAuthTokenRepository.class, () -> mock(OAuthTokenRepository.class))
            .withBean(UserAccountRepository.class, DryRunWiringTest::noActiveAccount)
            .withBean(UserSettingsRepository.class, () -> mock(UserSettingsRepository.class))
            .withBean(RestClient.class, () -> mock(RestClient.class))
            .withBean(TwitchCategoryService.class, () -> mock(TwitchCategoryService.class))
            .withBean(TwitchTokenService.class, () -> mock(TwitchTokenService.class))
            .withBean(ExternalApiObservations.class, () -> mock(ExternalApiObservations.class))
            .withBean(BotToggleService.class, () -> mock(BotToggleService.class))
            .withBean(CatapultCategoryChangeStateService.class, () -> mock(CatapultCategoryChangeStateService.class))
            .withBean(SiteRole.class, DryRunWiringTest::activeSite)
            .withBean(StreamTitleService.class, () -> mock(StreamTitleService.class))
            .withBean(TokenEncryptionService.class, () -> mock(TokenEncryptionService.class))
            .withBean(TwitchHelixChannelClient.class, () -> mock(TwitchHelixChannelClient.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(TwitchChatRateLimiter.class, () -> mock(TwitchChatRateLimiter.class))
            // the EventSub transport, when chosen
            .withBean(EventSubTwitchChatService.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SystemTwitchAccountService.class, () -> mock(SystemTwitchAccountService.class))
            .withBean(DebugRecording.class, () -> mock(DebugRecording.class))
            // the simulation, when on
            .withBean(SimulatedTwitchService.class)
            .withBean(SimulatedTwitchChatService.class)
            .withBean(SimulatedCallRunner.class, () -> mock(SimulatedCallRunner.class))
            .withBean(SimulationRegistry.class, () -> mock(SimulationRegistry.class))
            .withBean(SimulatedTwitchCategoryService.class, () -> mock(SimulatedTwitchCategoryService.class))
            .withBean(SimulatedTwitchTokenService.class, () -> mock(SimulatedTwitchTokenService.class))
            .withBean(ChannelUpdateEcho.class, () -> mock(ChannelUpdateEcho.class));

    private static DryRunGate holdingGate() {
        DryRunGate gate = mock(DryRunGate.class);
        when(gate.holdsChannel(any())).thenReturn(true);
        when(gate.holdsChat(any())).thenReturn(true);
        return gate;
    }

    private static UserAccountRepository noActiveAccount() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.findByBotEnabledTrueAndStatus(any())).thenReturn(List.of());
        return users;
    }

    private static SiteRole activeSite() {
        SiteRole siteRole = mock(SiteRole.class);
        when(siteRole.isActive()).thenReturn(true);
        return siteRole;
    }

    private static UserAccount member() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        return account;
    }

    @Test
    void gatesTheRealClientsWithTheSimulationOff() {
        runner.withPropertyValues("catapult.simulation.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            Consumer consumer = context.getBean(Consumer.class);
            UserAccount account = member();
            DryRunGate gate = context.getBean(DryRunGate.class);

            assertThat(consumer.twitch()).isInstanceOf(TwitchServiceImpl.class);
            assertThat(consumer.twitch().setCategory(account, "33214", "Factorio"))
                    .isEqualTo(ChannelUpdateOutcome.DRY_RUN);
            consumer.chat().sendMessage(account, "hi");

            assertThat(consumer.chat()).isInstanceOf(IrcTwitchChatService.class);
            assertThat(consumer.irc().getIfAvailable()).isSameAs(consumer.chat());
            assertThat(consumer.realTwitch().getIfAvailable()).isSameAs(consumer.twitch());
            verify(gate).note(account, "setCategory", "[dry run] would have changed the channel: category Factorio");
            verify(gate).note(account, "sendMessage", "[dry run] would have sent: hi");
            verifyNoInteractions(context.getBean(RestClient.class), context.getBean(TwitchChatRateLimiter.class),
                    context.getBean(ExternalApiObservations.class));
        });
    }

    @Test
    void wrapsTheSimulatedServicesToo() {
        runner.withPropertyValues("catapult.simulation.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            Consumer consumer = context.getBean(Consumer.class);
            UserAccount account = member();

            assertThat(consumer.twitch()).isInstanceOf(SimulatedTwitchService.class);
            assertThat(consumer.chat()).isInstanceOf(SimulatedTwitchChatService.class);
            assertThat(consumer.twitch().setCategory(account, "33214", "Factorio"))
                    .isEqualTo(ChannelUpdateOutcome.DRY_RUN);
            consumer.chat().ban(account, "troll", "rude");

            // the concrete real client, which the simulated one delegates to, still resolves
            assertThat(consumer.realTwitch().getIfAvailable()).isInstanceOf(TwitchServiceImpl.class);
            assertThat(consumer.irc().getIfAvailable()).isInstanceOf(IrcTwitchChatService.class);
            verify(context.getBean(DryRunGate.class)).note(account, "ban", "[dry run] would have banned troll");
            // a dry-run member reaches neither Twitch nor the simulator
            verifyNoInteractions(context.getBean(SimulatedCallRunner.class), context.getBean(RestClient.class),
                    context.getBean(ChannelUpdateEcho.class));
        });
    }

    @Test
    void gatesTheEventSubTransportAndKeepsItsCallbacks() {
        AtomicReference<Object> transport = new AtomicReference<>();
        runner.withPropertyValues("catapult.simulation.enabled=false", "app.chat.provider=eventsub").run(context -> {
            assertThat(context).hasNotFailed();
            Consumer consumer = context.getBean(Consumer.class);
            UserAccount account = member();
            UserAccountRepository users = context.getBean(UserAccountRepository.class);
            assertThat(consumer.chat()).isInstanceOf(EventSubTwitchChatService.class);
            assertThat(context.getBeanProvider(EventSubTwitchChatService.class).getIfAvailable())
                    .isSameAs(consumer.chat());

            consumer.chat().sendMessage(account, "hi");
            context.publishEvent(new AccountCreatedEvent(this, account));

            verify(context.getBean(DryRunGate.class)).note(account, "sendMessage", "[dry run] would have sent: hi");
            verifyNoInteractions(context.getBean(RestClient.class), context.getBean(TwitchChatRateLimiter.class));
            // @PostConstruct ran once, on the bean itself
            verify(users, times(1)).findByStatus(UserAccount.Status.ACTIVE);
            // @EventListener reached it
            verify(context.getBean(OAuthTokenRepository.class))
                    .findByUserAndProvider(account, OAuthToken.Provider.TWITCH);
            transport.set(AopTestUtils.getUltimateTargetObject(consumer.chat()));
        });
        // @PreDestroy ran on closing: the watchdogs of the bean's connections are stopped
        Object connections = ReflectionTestUtils.getField(transport.get(), "connections");
        assertThat((ExecutorService) ReflectionTestUtils.getField(connections, "watchdogExecutor"))
                .returns(true, ExecutorService::isShutdown);
    }

    @Test
    void aWrappedClientKeepsItsEventListeners() {
        runner.withPropertyValues("catapult.simulation.enabled=false").run(context -> {
            UserAccount account = member();

            context.publishEvent(new AccountCreatedEvent(this, account));

            verify(context.getBean(OAuthTokenRepository.class))
                    .findByUserAndProvider(account, OAuthToken.Provider.TWITCH);
        });
    }
}
