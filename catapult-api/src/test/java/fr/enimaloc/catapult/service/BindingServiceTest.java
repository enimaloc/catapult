package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.GameBindingRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BindingServiceTest {

    @Mock private GameBindingRepository gameBindingRepository;
    @Mock private IgdbService igdbService;
    @Mock private TwitchService twitchService;

    private BindingService bindingService;

    private UserAccount user;
    private GameBinding binding;
    private UUID bindingId;

    @BeforeEach
    void setup() {
        bindingService = new BindingService(gameBindingRepository, igdbService, twitchService);

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
        when(gameBindingRepository.findByIdAndUser(bindingId, user)).thenReturn(Optional.of(binding));
        when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        when(gameBindingRepository.findByIdAndUser(bindingId, user)).thenReturn(Optional.empty());

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
    void deleteBinding_deletesBinding() {
        bindingService.deleteBinding(user, bindingId);

        verify(gameBindingRepository).delete(binding);
    }

    @Test
    void resolveOrCreate_existingAutoBinding_returnsExisting() {
        DetectedGame game = new DetectedGame("steam-123", GameBinding.SourceType.STEAM, "Portal");
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "steam-123", GameBinding.SourceType.STEAM))
            .thenReturn(Optional.of(binding));

        GameBinding result = bindingService.resolveOrCreate(user, game);

        assertThat(result).isSameAs(binding);
        verifyNoInteractions(igdbService);
    }

    @Test
    void resolveOrCreate_noExisting_igdbFound_createsAutoBinding() {
        DetectedGame game = new DetectedGame("steam-123", GameBinding.SourceType.STEAM, "Portal");
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "steam-123", GameBinding.SourceType.STEAM))
            .thenReturn(Optional.empty());
        IgdbService.IgdbGame igdbGame = new IgdbService.IgdbGame("1234", "Portal");
        when(igdbService.findBySteamAppId("steam-123")).thenReturn(Optional.of(igdbGame));
        when(igdbService.findTwitchGameId("1234")).thenReturn(Optional.of("twitch-123"));
        when(igdbService.suggestCcls("1234")).thenReturn(Set.of());
        when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GameBinding result = bindingService.resolveOrCreate(user, game);

        assertThat(result.getStatus()).isEqualTo(GameBinding.Status.AUTO);
        assertThat(result.getTwitchGameId()).isEqualTo("twitch-123");
    }

    @Test
    void resolveOrCreate_noExisting_igdbNotFound_createsIncompleteBinding() {
        DetectedGame game = new DetectedGame(null, GameBinding.SourceType.MANUAL, "Unknown Game");
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, null, GameBinding.SourceType.MANUAL))
            .thenReturn(Optional.empty());
        when(igdbService.findByName("Unknown Game")).thenReturn(Optional.empty());
        when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GameBinding result = bindingService.resolveOrCreate(user, game);

        assertThat(result.getStatus()).isEqualTo(GameBinding.Status.INCOMPLETE);
    }

    @Test
    void resolveOrCreate_noExisting_igdbFoundButNoTwitchId_createsIncompleteBinding() {
        DetectedGame game = new DetectedGame("steam-123", GameBinding.SourceType.STEAM, "Portal");
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "steam-123", GameBinding.SourceType.STEAM))
            .thenReturn(Optional.empty());
        IgdbService.IgdbGame igdbGame = new IgdbService.IgdbGame("1234", "Portal");
        when(igdbService.findBySteamAppId("steam-123")).thenReturn(Optional.of(igdbGame));
        when(igdbService.findTwitchGameId("1234")).thenReturn(Optional.empty());
        when(twitchService.findCategoryIdByName(user, "Portal")).thenReturn(Optional.empty());
        when(igdbService.suggestCcls("1234")).thenReturn(Set.of());
        when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GameBinding result = bindingService.resolveOrCreate(user, game);

        assertThat(result.getStatus()).isEqualTo(GameBinding.Status.INCOMPLETE);
        assertThat(result.getTwitchGameId()).isNull();
    }

    @Test
    void resolveOrCreate_existingIncomplete_igdbFound_updatesBinding() {
        DetectedGame game = new DetectedGame("steam-123", GameBinding.SourceType.STEAM, "Portal");
        GameBinding incompleteBinding = new GameBinding();
        incompleteBinding.setUser(user);
        incompleteBinding.setStatus(GameBinding.Status.INCOMPLETE);
        when(gameBindingRepository.findByUserAndSourceIdAndSourceType(user, "steam-123", GameBinding.SourceType.STEAM))
            .thenReturn(Optional.of(incompleteBinding));
        IgdbService.IgdbGame igdbGame = new IgdbService.IgdbGame("1234", "Portal");
        when(igdbService.findBySteamAppId("steam-123")).thenReturn(Optional.of(igdbGame));
        when(igdbService.findTwitchGameId("1234")).thenReturn(Optional.empty());
        when(twitchService.findCategoryIdByName(user, "Portal")).thenReturn(Optional.of("twitch-456"));
        when(igdbService.suggestCcls("1234")).thenReturn(Set.of());
        when(gameBindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GameBinding result = bindingService.resolveOrCreate(user, game);

        assertThat(result.getStatus()).isEqualTo(GameBinding.Status.AUTO);
        assertThat(result.getTwitchGameId()).isEqualTo("twitch-456");
    }
}
