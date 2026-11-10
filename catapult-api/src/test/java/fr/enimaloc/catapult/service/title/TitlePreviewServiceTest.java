package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.common.dto.channel.TitlePreviewRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.title.StreamTitleState;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.title.StreamTitleStateRepository;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.drops.DropsCatalog;
import fr.enimaloc.catapult.service.drops.DropsGate;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TitlePreviewServiceTest {

    private final ExperimentService experiments = mock(ExperimentService.class);
    private final StreamTitleStateRepository states = mock(StreamTitleStateRepository.class);
    private final GameBindingRepository bindings = mock(GameBindingRepository.class);
    private final CustomBindingRepository customBindings = mock(CustomBindingRepository.class);
    private final UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final GameBindingLookup lookup = mock(GameBindingLookup.class);
    private final StreamTitleService titles = new StreamTitleService(experiments, states, mock(DropsGate.class),
            mock(DropsCatalog.class));
    private final TitlePreviewService previews = new TitlePreviewService(titles, states, bindings, customBindings,
            settingsRepository, gameState, lookup);
    private UserAccount user;
    private final UserSettings settings = new UserSettings();

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(settingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(states.findById(user.getId())).thenReturn(Optional.empty());
        when(experiments.isRolledOut(user, StreamTitleService.CONDITIONS_KEY)).thenReturn(true);
    }

    private static GameBinding binding(String game, String category) {
        GameBinding b = new GameBinding();
        b.setId(UUID.randomUUID());
        b.setSourceName(game);
        b.setTwitchGameName(category);
        b.setTwitchGameId("1");
        return b;
    }

    private Optional<String> preview(String template, String kind, UUID bindingId, UUID customId) {
        return previews.preview(user, new TitlePreviewRequest(template, kind, bindingId, customId));
    }

    @Test
    void preview_usesTheBindingsContext() {
        GameBinding doom = binding("Doom Eternal", "DOOM");
        when(bindings.findByIdAndUser(doom.getId(), user)).thenReturn(Optional.of(doom));

        assertThat(preview("{{GAME}} | {{CATEGORY}} {{HAS_GAME ? [[on]] : [[off]]}}", "BINDING", doom.getId(), null))
                .contains("Doom Eternal | DOOM on");
    }

    @Test
    void preview_customBinding_isItsNameAndCategory() {
        CustomBinding art = new CustomBinding();
        art.setId(UUID.randomUUID());
        art.setName("Art");
        art.setTwitchGameId("509660");
        art.setTwitchGameName("Art");
        when(customBindings.findByIdAndUser(art.getId(), user)).thenReturn(Optional.of(art));

        assertThat(preview("{{IS_CUSTOM ? [[{{GAME}}!]] : [[no]]}}", "CUSTOM", null, art.getId())).contains("Art!");
    }

    @Test
    void preview_template_usesTheGamePlayedNow_elseAnExample() {
        GameBinding hades = binding("Hades II", "Hades II");
        when(gameState.getLastKnownGame(user)).thenReturn(Optional.of(new DetectedGame("1", GameBinding.SourceType.STEAM, "Hades II")));
        when(lookup.findByDetectedGame(any(), any())).thenReturn(Optional.of(hades));
        assertThat(preview("Live {{GAME}}", "TEMPLATE", null, null)).contains("Live Hades II");

        when(gameState.getLastKnownGame(user)).thenReturn(Optional.empty());
        assertThat(preview("Live {{GAME}}", "TEMPLATE", null, null)).contains("Live " + TitlePreviewService.EXAMPLE_GAME);
    }

    @Test
    void preview_noGame_andIncomplete_useTheirCards() {
        settings.setNoGameTwitchGameName("Just Chatting");
        settings.setIncompleteFallbackTwitchGameName("Games + Demos");
        assertThat(preview("{{IS_NO_GAME ? [[{{CATEGORY}}]] : [[x]]}}", "NO_GAME", null, null)).contains("Just Chatting");
        assertThat(preview("{{IS_INCOMPLETE ? [[{{CATEGORY}}]] : [[x]]}}", "INCOMPLETE", null, null))
                .contains("Games + Demos");
    }

    @Test
    void preview_titles_areTheLastKnownOnes_withoutTwitch() {
        StreamTitleState state = new StreamTitleState(user.getId());
        state.setLastSentTitle("Chill — Doom");
        state.setOriginalTitle("Chill");
        when(states.findById(user.getId())).thenReturn(Optional.of(state));

        assertThat(preview("{{ORIGINAL_TITLE}} / {{TITLE}}", "NO_GAME", null, null)).contains("Chill / Chill — Doom");
    }

    @Test
    void preview_malformedShowsRaw_andAnUnknownTitleLeavesItAsItIs() {
        assertThat(preview("{{HAS_GAME ? [[x]]}}", "NO_GAME", null, null)).contains("{{HAS_GAME ? [[x]]}}");
        assertThat(preview("{{TITLE}}", "NO_GAME", null, null)).isEmpty();
    }

    @Test
    void anEmptyOwnTitle_isPreviewedAsTheOneThatWouldBeSent_theChannelWideOne() {
        settings.setTitleTemplate("Live {{GAME}}");
        settings.setNoGameTwitchGameName("Just Chatting");
        GameBinding doom = binding("Doom", "DOOM");
        when(bindings.findByIdAndUser(doom.getId(), user)).thenReturn(Optional.of(doom));

        assertThat(preview("  ", "BINDING", doom.getId(), null)).contains("Live Doom");
        assertThat(preview("", "INCOMPLETE", null, null)).contains("Live " + TitlePreviewService.EXAMPLE_GAME);
        assertThat(preview(null, "NO_GAME", null, null)).contains("Live");
        assertThat(preview("", "TEMPLATE", null, null)).as("the template itself has none behind it").isEmpty();
    }

    @Test
    void preview_refusesATextLongerThanATitle_beforeRenderingIt() {
        String deep = "{{HAS_GAME ? [[".repeat(5000) + "x" + "]] : [[]]}}".repeat(5000);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> preview(deep, "NO_GAME", null, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
