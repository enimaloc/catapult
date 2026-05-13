package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.ObsProcessCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObsGameGetterTest {

    @Mock private ObsProcessCache obsProcessCache;
    @Mock private ProcessBindingRepository processBindingRepository;

    @InjectMocks private ObsGameGetter getter;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getCurrentGame_noProcesses_returnsEmpty() {
        when(obsProcessCache.getProcesses(user)).thenReturn(Set.of());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_processesWithNoBinding_returnsEmpty() {
        when(obsProcessCache.getProcesses(user)).thenReturn(Set.of("unknown.exe"));
        when(processBindingRepository.findFirstByUserAndProcessNameIn(user, Set.of("unknown.exe")))
                .thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_matchingBinding_returnsDetectedGame() {
        ProcessBinding binding = new ProcessBinding();
        binding.setProcessName("hl2.exe");
        binding.setTwitchGameId("70");
        binding.setTwitchGameName("Half-Life 2");

        when(obsProcessCache.getProcesses(user)).thenReturn(Set.of("hl2.exe", "steam.exe"));
        when(processBindingRepository.findFirstByUserAndProcessNameIn(user, Set.of("hl2.exe", "steam.exe")))
                .thenReturn(Optional.of(binding));

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("hl2.exe");
        assertThat(result.get().getSourceType()).isEqualTo(GameBinding.SourceType.OBS);
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    }
}
