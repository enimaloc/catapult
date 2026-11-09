package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamTitleServiceTest {

    @Mock ExperimentService experimentService;

    StreamTitleService service;
    UserAccount user;
    UserSettings settings;

    @BeforeEach
    void setUp() {
        service = new StreamTitleService(experimentService);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        settings = new UserSettings();
        settings.setTitleFeatureEnabled(true);
        settings.setTitleTemplate("Playing {game} with chat");
        when(experimentService.isRolledOut(user, StreamTitleService.EXPERIMENT_KEY)).thenReturn(true);
    }

    private static GameBinding binding(String gameName, String title) {
        GameBinding binding = new GameBinding();
        binding.setTwitchGameName(gameName);
        binding.setTitle(title);
        return binding;
    }

    @Test
    void theChannelWideTemplate_namesTheCategory() {
        assertThat(service.titleFor(user, settings, binding("Hades II", null))).contains("Playing Hades II with chat");
    }

    @Test
    void aGamesOwnTitle_winsOverTheTemplate() {
        assertThat(service.titleFor(user, settings, binding("Hades II", "{game} — blind run")))
                .contains("Hades II — blind run");
    }

    @Test
    void aBlankGameTitle_fallsBackToTheTemplate() {
        assertThat(service.titleFor(user, settings, binding("Hades II", "  "))).contains("Playing Hades II with chat");
    }

    @Test
    void nothing_whenTheStreamerHasntTurnedItOn() {
        settings.setTitleFeatureEnabled(false);

        assertThat(service.titleFor(user, settings, binding("Hades II", "Mine"))).isEmpty();
        assertThat(service.titleForNoGame(user, settings)).isEmpty();
        assertThat(service.titleFor(user, null, binding("Hades II", "Mine"))).isEmpty();
    }

    @Test
    void nothing_outsideTheExperiment_whichIsOnlyRead() {
        when(experimentService.isRolledOut(user, StreamTitleService.EXPERIMENT_KEY)).thenReturn(false);

        assertThat(service.titleFor(user, settings, binding("Hades II", "Mine"))).isEmpty();
        verify(experimentService, never()).evaluateGate(any(), anyString());
    }

    @Test
    void nothing_withoutATitleOrTemplate() {
        settings.setTitleTemplate(null);

        assertThat(service.titleFor(user, settings, binding("Hades II", null))).isEmpty();
    }

    @Test
    void theNoGameTitle_usesTheTemplateWithTheNoGameCategory() {
        settings.setNoGameTwitchGameName("Just Chatting");

        assertThat(service.titleForNoGame(user, settings)).contains("Playing Just Chatting with chat");
    }

    @Test
    void render_cutsToTwitchsLimit_andNeverSendsAnEmptyTitle() {
        String long150 = "x".repeat(150);
        assertThat(StreamTitleService.render(long150, "g")).hasValueSatisfying(t -> assertThat(t).hasSize(140));
        assertThat(StreamTitleService.render("  {game}  ", null)).isEmpty();
        assertThat(StreamTitleService.render("🎮".repeat(141), "g"))
                .hasValueSatisfying(t -> assertThat(t.codePointCount(0, t.length())).isEqualTo(140));
    }
}
