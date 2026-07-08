package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameGetterChainTest {

    @Mock GetterConfigRepository getterConfigRepository;
    @Mock SteamGameGetter steamGameGetter;

    GameGetterChain chain;
    SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        chain = new GameGetterChain(getterConfigRepository, Optional.of(steamGameGetter), Optional.empty(), Optional.empty(), Optional.empty(), registry);
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
}
