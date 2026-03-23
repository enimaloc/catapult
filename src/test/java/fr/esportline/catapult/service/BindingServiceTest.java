package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.GameBindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BindingServiceTest {

    @Mock private GameBindingRepository gameBindingRepository;
    @Mock private IgdbService igdbService;
    @Mock private TwitchService twitchService;

    @InjectMocks private BindingService bindingService;

    private UserAccount user;
    private GameBinding binding;
    private UUID bindingId;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        bindingId = UUID.randomUUID();

        binding = new GameBinding();
        binding.setUser(user);
        binding.setStatus(GameBinding.Status.AUTO);
        binding.setTwitchGameId("old-game-id");
        binding.setTwitchGameName("Old Game");
        binding.getCcls().add(TwitchCcl.ViolentGraphic);
        binding.getCcls().add(TwitchCcl.Gambling);

        when(gameBindingRepository.findById(bindingId)).thenReturn(Optional.of(binding));
        when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void updateBinding_replacesCclsInPlace() {
        Set<TwitchCcl> newCcls = Set.of(TwitchCcl.SexualThemes);

        bindingService.updateBinding(user, bindingId, "new-id", "New Game", newCcls, false);

        assertThat(binding.getCcls()).containsExactly(TwitchCcl.SexualThemes);
    }

    @Test
    void updateBinding_withEmptyCcls_clearsCcls() {
        bindingService.updateBinding(user, bindingId, "new-id", "New Game", Set.of(), false);

        assertThat(binding.getCcls()).isEmpty();
    }

    @Test
    void updateBinding_setsStatusToManualWhenGameIdProvided() {
        bindingService.updateBinding(user, bindingId, "new-id", "New Game", Set.of(), false);

        assertThat(binding.getStatus()).isEqualTo(GameBinding.Status.MANUAL);
    }

    @Test
    void updateBinding_callsTwitchUpdateChannel() {
        bindingService.updateBinding(user, bindingId, "new-id", "New Game", Set.of(), false);

        verify(twitchService).updateChannel(user, binding);
    }

    @Test
    void updateBinding_unknownId_doesNothing() {
        when(gameBindingRepository.findById(bindingId)).thenReturn(Optional.empty());

        bindingService.updateBinding(user, bindingId, "new-id", "New Game", Set.of(), false);

        verifyNoInteractions(twitchService);
    }

    @Test
    void toggleCclEnabled_updatesFieldAndCallsTwitch() {
        binding.setCclEnabled(true);

        bindingService.toggleCclEnabled(user, bindingId, false);

        assertThat(binding.isCclEnabled()).isFalse();
        verify(twitchService).updateChannel(user, binding);
    }

    @Test
    void toggleIgnored_updatesFieldAndCallsTwitch() {
        binding.setIgnored(false);

        bindingService.toggleIgnored(user, bindingId, true);

        assertThat(binding.isIgnored()).isTrue();
        verify(twitchService).updateChannel(user, binding);
    }

    @Test
    void deleteBinding_deletesById() {
        bindingService.deleteBinding(bindingId);

        verify(gameBindingRepository).deleteById(bindingId);
    }
}
