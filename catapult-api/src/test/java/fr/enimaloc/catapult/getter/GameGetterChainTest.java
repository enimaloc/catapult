package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.steam.SteamGameGetter;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.emergency.EmergencyStopService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameGetterChainTest {

    @Mock GetterConfigRepository getterConfigRepository;
    @Mock SteamGameGetter steamGameGetter;
    @Mock XboxGameGetter xboxGameGetter;
    @Mock EmergencyStopService emergencyStops;

    GameGetterChain chain;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        chain = new GameGetterChain(getterConfigRepository, Optional.of(steamGameGetter), Optional.of(xboxGameGetter), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), registry, emergencyStops);
    }

    @Test
    void resolve_records_detection_timer_with_resolved_by_tag() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        GetterConfig steamConfig = new GetterConfig();
        steamConfig.setProvider(GetterConfig.Provider.STEAM);
        steamConfig.setEnabled(true);
        steamConfig.setPriority(1);

        DetectedGame detectedGame = new DetectedGame("123", GameBinding.SourceType.STEAM, "TestGame");

        when(getterConfigRepository.findByUserOrderByPriorityAsc(user)).thenReturn(List.of(steamConfig));
        when(steamGameGetter.getCurrentGame(user)).thenReturn(Optional.of(detectedGame));

        chain.resolve(user);

        assertThat(registry.get("catapult.game.detection.duration")
                .tag("resolved_by", "steam").timer().count()).isEqualTo(1);
    }

    @Test
    void resolve_rethrowsATimeout_ratherThanTryingTheNextGetter() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());

        GetterConfig steamConfig = new GetterConfig();
        steamConfig.setProvider(GetterConfig.Provider.STEAM);
        steamConfig.setEnabled(true);
        steamConfig.setPriority(1);

        when(getterConfigRepository.findByUserOrderByPriorityAsc(user)).thenReturn(List.of(steamConfig));
        when(steamGameGetter.getCurrentGame(user))
            .thenThrow(new DetectionTimeoutException("Steam", new TimeoutException()));

        assertThatThrownBy(() -> chain.resolve(user)).isInstanceOf(DetectionTimeoutException.class);
        assertThat(registry.get("catapult.game.detection.duration")
                .tag("resolved_by", "timeout").timer().count()).isEqualTo(1);
    }

    private GetterConfig config(GetterConfig.Provider provider, int priority) {
        GetterConfig config = new GetterConfig();
        config.setProvider(provider);
        config.setEnabled(true);
        config.setPriority(priority);
        return config;
    }

    @Test
    void resolve_stoppedProviderFirst_throwsProviderStopped() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user))
            .thenReturn(List.of(config(GetterConfig.Provider.XBOX, 1), config(GetterConfig.Provider.STEAM, 2)));
        when(emergencyStops.isStopped(user, GetterConfig.Provider.XBOX)).thenReturn(true);

        assertThatThrownBy(() -> chain.resolve(user))
            .isInstanceOf(ProviderStoppedException.class)
            .isInstanceOf(DetectionTimeoutException.class)
            .hasMessage("XBOX is suspended by an emergency stop")
            .extracting(e -> ((ProviderStoppedException) e).getProvider()).isEqualTo(GetterConfig.Provider.XBOX);
        verifyNoInteractions(xboxGameGetter);
    }

    @Test
    void resolve_higherPriorityFindsGame_beforeStoppedProvider_returnsIt() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        DetectedGame game = new DetectedGame("123", GameBinding.SourceType.STEAM, "TestGame");
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user))
            .thenReturn(List.of(config(GetterConfig.Provider.STEAM, 1), config(GetterConfig.Provider.XBOX, 2)));
        when(emergencyStops.isStopped(user, GetterConfig.Provider.STEAM)).thenReturn(false);
        when(steamGameGetter.getCurrentGame(user)).thenReturn(Optional.of(game));

        assertThat(chain.resolve(user)).contains(game);
        verifyNoInteractions(xboxGameGetter);
    }

    @Test
    void resolve_stoppedProvider_doesNotCallLowerPriorityGetters() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(user))
            .thenReturn(List.of(config(GetterConfig.Provider.XBOX, 1), config(GetterConfig.Provider.STEAM, 2)));
        when(emergencyStops.isStopped(user, GetterConfig.Provider.XBOX)).thenReturn(true);

        assertThatThrownBy(() -> chain.resolve(user)).isInstanceOf(ProviderStoppedException.class);
        verifyNoInteractions(steamGameGetter);
    }
}
