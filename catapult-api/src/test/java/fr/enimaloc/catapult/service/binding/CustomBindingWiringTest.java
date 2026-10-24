package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.event.listener.GameEventListener;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.igdb.IgdbGameDetailsService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.notification.TwitchatEventDispatcher;
import fr.enimaloc.catapult.service.tw.TwResolverService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Mockito tests build each bean by hand and never see a Spring dependency cycle: this one wires the real custom
 * binding beans and the detection path ({@link GameEventListener}, {@link BindingService}) together, with circular
 * references forbidden, so a dependency added on either side that closes a loop fails here.
 */
class CustomBindingWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withAllowCircularReferences(false)
            // IgdbService is @DependsOn("flyway"): a stand-in is enough here
            .withBean("flyway", Object.class, Object::new)
            .withBean(CustomBindingActivations.class)
            .withBean(CustomBindingService.class)
            .withBean(GameEventListener.class)
            .withBean(BindingService.class)
            .withBean(GameStateService.class)
            .withBean(StreamStateService.class)
            .withBean(RaidRestoreGameService.class)
            .withBean(RaidRestoreGateService.class, () -> mock(RaidRestoreGateService.class))
            .withBean(GameMappingRepository.class, () -> mock(GameMappingRepository.class))
            .withBean(CustomBindingRepository.class, () -> mock(CustomBindingRepository.class))
            .withBean(CustomBindingActivationRepository.class, () -> mock(CustomBindingActivationRepository.class))
            .withBean(GameBindingRepository.class, () -> mock(GameBindingRepository.class))
            .withBean(UserSettingsRepository.class, () -> mock(UserSettingsRepository.class))
            .withBean(UserAccountRepository.class, () -> mock(UserAccountRepository.class))
            .withBean(OAuthTokenRepository.class, () -> mock(OAuthTokenRepository.class))
            .withBean(TwitchService.class, () -> mock(TwitchService.class))
            .withBean(ExperimentService.class, () -> mock(ExperimentService.class))
            .withBean(ChannelEventPublisher.class, () -> mock(ChannelEventPublisher.class))
            .withBean(TwitchatEventDispatcher.class, () -> mock(TwitchatEventDispatcher.class))
            .withBean(IgdbService.class, () -> mock(IgdbService.class))
            .withBean(IgdbGameDetailsService.class, () -> mock(IgdbGameDetailsService.class))
            .withBean(TwResolverService.class, () -> mock(TwResolverService.class));

    @Test
    void theCustomBindingBeansAndTheDetectionPath_wireWithoutACycle() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CustomBindingService.class);
            assertThat(context).hasSingleBean(CustomBindingActivations.class);
            assertThat(context).hasSingleBean(GameEventListener.class);
        });
    }

    @Test
    void theRunnerDoesCatchACycle() {
        // Control: the same runner fails on a real constructor cycle, so the test above proves something
        new ApplicationContextRunner()
                .withAllowCircularReferences(false)
                .withBean(CycleA.class)
                .withBean(CycleB.class)
                .run(context -> assertThat(context).hasFailed());
    }

    static class CycleA {
        CycleA(CycleB b) {}
    }

    static class CycleB {
        CycleB(CycleA a) {}
    }
}
