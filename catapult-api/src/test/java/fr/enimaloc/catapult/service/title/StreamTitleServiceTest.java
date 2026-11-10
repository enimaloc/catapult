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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
        settings.setTitleTemplate("Playing {{GAME}} with chat");
        when(experimentService.isRolledOut(user, StreamTitleService.EXPERIMENT_KEY)).thenReturn(true);
    }

    private static final Supplier<Optional<String>> UNREAD = () -> {
        throw new AssertionError("the current title was read without {{TITLE}}");
    };

    private static GameBinding binding(String gameName, String title) {
        GameBinding binding = new GameBinding();
        binding.setTwitchGameName(gameName);
        binding.setTitle(title);
        return binding;
    }

    @Test
    void theChannelWideTemplate_namesTheCategory() {
        assertThat(service.titleFor(user, settings, binding("Hades II", null), UNREAD)).contains("Playing Hades II with chat");
    }

    @Test
    void aGamesOwnTitle_winsOverTheTemplate() {
        assertThat(service.titleFor(user, settings, binding("Hades II", "{{game}} — blind run"), UNREAD))
                .contains("Hades II — blind run");
    }

    @Test
    void aBlankGameTitle_fallsBackToTheTemplate() {
        assertThat(service.titleFor(user, settings, binding("Hades II", "  "), UNREAD)).contains("Playing Hades II with chat");
    }

    @Test
    void nothing_whenTheStreamerHasntTurnedItOn() {
        settings.setTitleFeatureEnabled(false);

        assertThat(service.titleFor(user, settings, binding("Hades II", "Mine"), UNREAD)).isEmpty();
        assertThat(service.titleForNoGame(user, settings, UNREAD)).isEmpty();
        assertThat(service.titleFor(user, null, binding("Hades II", "Mine"), UNREAD)).isEmpty();
    }

    @Test
    void nothing_outsideTheExperiment_whichIsOnlyRead() {
        when(experimentService.isRolledOut(user, StreamTitleService.EXPERIMENT_KEY)).thenReturn(false);

        assertThat(service.titleFor(user, settings, binding("Hades II", "Mine"), UNREAD)).isEmpty();
        verify(experimentService, never()).evaluateGate(any(), anyString());
    }

    @Test
    void nothing_withoutATitleOrTemplate() {
        settings.setTitleTemplate(null);

        assertThat(service.titleFor(user, settings, binding("Hades II", null), UNREAD)).isEmpty();
    }

    @Test
    void theNoGameTitle_usesTheTemplateWithTheNoGameCategory() {
        settings.setNoGameTwitchGameName("Just Chatting");

        assertThat(service.titleForNoGame(user, settings, UNREAD)).contains("Playing Just Chatting with chat");
    }

    @Test
    void render_cutsToTwitchsLimit_andNeverSendsAnEmptyTitle() {
        String long150 = "x".repeat(150);
        assertThat(StreamTitleService.render(long150, "g", UNREAD)).hasValueSatisfying(t -> assertThat(t).hasSize(140));
        assertThat(StreamTitleService.render("  {{GAME}}  ", null, UNREAD)).isEmpty();
        assertThat(StreamTitleService.render("🎮".repeat(141), "g", UNREAD))
                .hasValueSatisfying(t -> assertThat(t.codePointCount(0, t.length())).isEqualTo(140));
    }

    @Test
    void render_anyCaseAndSpacing_andLeavesOtherNamesAsTyped() {
        assertThat(StreamTitleService.render("{{ Game }} & {{OTHER}} & {game} & $1", "Doom", UNREAD))
                .contains("Doom & {{OTHER}} & {game} & $1");
    }

    @Test
    void render_titleIsTheCurrentTitle_readOnceAndOnlyWhenUsed() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<Optional<String>> current = () -> {
            reads.incrementAndGet();
            return Optional.of("Chill $ night");
        };

        assertThat(StreamTitleService.render("{{TITLE}} | {{GAME}} | {{title}}", "Doom", current))
                .contains("Chill $ night | Doom | Chill $ night");
        assertThat(reads).hasValue(1);
    }

    @Test
    void render_nothing_whenTheCurrentTitleCantBeRead() {
        assertThat(StreamTitleService.render("{{TITLE}} | {{GAME}}", "Doom", Optional::empty)).isEmpty();
    }
}
