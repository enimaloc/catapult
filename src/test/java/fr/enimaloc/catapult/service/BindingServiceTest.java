package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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

    private BindingService bindingService;
    private SimpleMeterRegistry meterRegistry;

    private UserAccount user;
    private GameBinding binding;
    private UUID bindingId;

    @BeforeEach
    void setup() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        bindingService = new BindingService(gameBindingRepository, igdbService, twitchService, meterRegistry);
        var m = BindingService.class.getDeclaredMethod("registerGauges");
        m.setAccessible(true);
        m.invoke(bindingService);

        user = new UserAccount();
        bindingId = UUID.randomUUID();

        binding = new GameBinding();
        binding.setUser(user);
        binding.setStatus(GameBinding.Status.AUTO);
        binding.setTwitchGameId("old-game-id");
        binding.setTwitchGameName("Old Game");
        binding.getCcls().add("ViolentGraphic");
        binding.getCcls().add("Gambling");

        when(gameBindingRepository.findById(bindingId)).thenReturn(Optional.of(binding));
        when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gameBindingRepository.countByIgnoredFalse()).thenReturn(3L);
    }

    @Test
    void updateBinding_replacesCclsInPlace() {
        Set<String> newCcls = Set.of("SexualThemes");

        bindingService.updateBinding(user, bindingId, "new-id", "New Game", newCcls, false);

        assertThat(binding.getCcls()).containsExactly("SexualThemes");
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

    @Test
    void deleteBinding_incrementsDeletedCounter() {
        bindingService.deleteBinding(bindingId);

        assertThat(meterRegistry.counter("catapult.bindings.deleted").count()).isEqualTo(1.0);
    }

    @Test
    void updateBinding_doesNotIncrementDeletedCounter() {
        bindingService.updateBinding(user, bindingId, "new-id", "New Game", Set.of(), false);

        assertThat(meterRegistry.counter("catapult.bindings.deleted").count()).isEqualTo(0.0);
    }

    @Test
    void gauge_exposesActiveBindingCount() {
        double gaugeValue = meterRegistry.get("catapult.bindings.active").gauge().value();

        assertThat(gaugeValue).isEqualTo(3.0);
    }
}
