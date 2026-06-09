package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.getter.SteamGameGetter;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerServiceTest {

    @Mock UserAccountRepository userAccountRepository;
    @Mock GameGetterChain gameGetterChain;
    @Mock GameStateService gameStateService;
    @Mock ApplicationEventPublisher eventPublisher;

    private SimpleMeterRegistry registry;
    private SchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        schedulerService = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.empty());
    }

    @Test
    void poll_recordsOneDurationSample_whenNoUsers() {
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of());

        schedulerService.poll();

        assertThat(registry.get("catapult.scheduler.poll.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void poll_countsEachProcessedUser() {
        UserAccount u1 = new UserAccount(); u1.setId(UUID.randomUUID());
        UserAccount u2 = new UserAccount(); u2.setId(UUID.randomUUID());
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u1, u2));
        when(gameGetterChain.resolve(any())).thenReturn(Optional.empty());
        when(gameStateService.getLastKnownGame(any())).thenReturn(Optional.empty());

        schedulerService.poll();

        assertThat(registry.get("catapult.scheduler.users.polled").counter().count())
            .isEqualTo(2.0);
    }

    @Test
    void poll_countsUserEvenWhenProcessUserThrows() {
        UserAccount u1 = new UserAccount(); u1.setId(UUID.randomUUID());
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of(u1));
        when(gameGetterChain.resolve(any())).thenThrow(new RuntimeException("simulated error"));

        schedulerService.poll();

        assertThat(registry.get("catapult.scheduler.users.polled").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void poll_withSteamGetter_callsPrefetchBatch() {
        SteamGameGetter steamGetter = mock(SteamGameGetter.class);
        when(steamGetter.prefetchBatch(any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(userAccountRepository.findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE))
            .thenReturn(List.of());

        SchedulerService service = new SchedulerService(
            userAccountRepository, gameGetterChain, gameStateService, eventPublisher, registry,
            Optional.of(steamGetter));

        service.poll();

        verify(steamGetter).prefetchBatch(any());
        verify(steamGetter).clearCycleCache();
    }
}
