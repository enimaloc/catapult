package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.title.StreamTitleState;
import fr.enimaloc.catapult.repository.title.StreamTitleStateRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import fr.enimaloc.catapult.service.title.StreamTitleService.PlannedTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamTitleServiceTest {

    @Mock ExperimentService experimentService;
    @Mock StreamTitleStateRepository stateRepository;

    StreamTitleService service;
    UserAccount user;
    UserSettings settings;

    @BeforeEach
    void setUp() {
        service = new StreamTitleService(experimentService, stateRepository);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        settings = new UserSettings();
        settings.setTitleFeatureEnabled(true);
        settings.setTitleTemplate("Playing {{GAME}} with chat");
        when(experimentService.isRolledOut(user, StreamTitleService.EXPERIMENT_KEY)).thenReturn(true);
        when(stateRepository.findById(user.getId())).thenReturn(Optional.empty());
    }

    private static Supplier<Optional<String>> current(String title) {
        return () -> Optional.ofNullable(title);
    }

    private static GameBinding binding(String gameName, String title) {
        GameBinding binding = new GameBinding();
        binding.setTwitchGameName(gameName);
        binding.setTitle(title);
        return binding;
    }

    private void remembered(String original, String lastSent) {
        StreamTitleState state = new StreamTitleState(user.getId());
        state.setOriginalTitle(original);
        state.setLastSentTitle(lastSent);
        when(stateRepository.findById(user.getId())).thenReturn(Optional.of(state));
    }

    private Optional<String> titleFor(GameBinding binding, Supplier<Optional<String>> current) {
        return service.titleFor(user, settings, binding, current).map(PlannedTitle::title);
    }

    @Test
    void theChannelWideTemplate_namesTheCategory() {
        assertThat(titleFor(binding("Hades II", null), current("Old"))).contains("Playing Hades II with chat");
    }

    @Test
    void aGamesOwnTitle_winsOverTheTemplate() {
        assertThat(titleFor(binding("Hades II", "{{GAME}} — blind run"), current("Old"))).contains("Hades II — blind run");
    }

    @Test
    void aBlankGameTitle_fallsBackToTheTemplate() {
        assertThat(titleFor(binding("Hades II", "  "), current("Old"))).contains("Playing Hades II with chat");
    }

    @Test
    void nothing_andNothingRead_whenTheStreamerHasntTurnedItOn() {
        settings.setTitleFeatureEnabled(false);
        AtomicInteger reads = new AtomicInteger();
        Supplier<Optional<String>> counted = () -> {
            reads.incrementAndGet();
            return Optional.of("Old");
        };

        assertThat(titleFor(binding("Hades II", "Mine"), counted)).isEmpty();
        assertThat(service.titleForNoGame(user, settings, counted)).isEmpty();
        assertThat(service.titleFor(user, null, binding("Hades II", "Mine"), counted)).isEmpty();
        assertThat(reads).hasValue(0);
    }

    @Test
    void nothing_outsideTheExperiment_whichIsOnlyRead() {
        when(experimentService.isRolledOut(user, StreamTitleService.EXPERIMENT_KEY)).thenReturn(false);

        assertThat(titleFor(binding("Hades II", "Mine"), current("Old"))).isEmpty();
        verify(experimentService, never()).evaluateGate(any(), anyString());
    }

    @Test
    void nothing_withoutATitleOrTemplate() {
        settings.setTitleTemplate(null);

        assertThat(titleFor(binding("Hades II", null), current("Old"))).isEmpty();
    }

    @Test
    void theNoGameTitle_usesTheTemplateWithTheNoGameCategory() {
        settings.setNoGameTwitchGameName("Just Chatting");

        assertThat(service.titleForNoGame(user, settings, current("Old")).map(PlannedTitle::title))
                .contains("Playing Just Chatting with chat");
    }

    @Test
    void theTitleBefore_isReadOnce_andCarriedForWhatIsRemembered() {
        settings.setTitleTemplate("{{TITLE}} | {{GAME}} | {{TITLE}}");
        AtomicInteger reads = new AtomicInteger();

        Optional<PlannedTitle> planned = service.titleFor(user, settings, binding("Doom", null), () -> {
            reads.incrementAndGet();
            return Optional.of("Chill $ night");
        });

        assertThat(planned).contains(new PlannedTitle("Chill $ night | Doom | Chill $ night", Optional.of("Chill $ night")));
        assertThat(reads).hasValue(1);
    }

    @Test
    void aTitleVariableThatCantBeRead_leavesTheTitleAlone_butOthersAreStillSent() {
        settings.setTitleTemplate("{{TITLE}} | {{GAME}}");
        assertThat(titleFor(binding("Doom", null), Optional::empty)).isEmpty();

        settings.setTitleTemplate("Now: {{GAME}}");
        assertThat(service.titleFor(user, settings, binding("Doom", null), Optional::empty))
                .contains(new PlannedTitle("Now: Doom", Optional.empty()));
    }

    @Test
    void theOriginalTitle_isTheStreamersOwn_untilCatapultChangedIt() {
        settings.setTitleTemplate("{{ORIGINAL_TITLE}} — {{GAME}}");

        // Never sent anything: the title found is the streamer's
        assertThat(titleFor(binding("Doom", null), current("Chill night"))).contains("Chill night — Doom");

        // Still the one Catapult sent: the remembered original
        remembered("Chill night", "Chill night — Doom");
        assertThat(titleFor(binding("Hades", null), current("Chill night — Doom"))).contains("Chill night — Hades");

        // Changed by hand since: the new one is theirs
        assertThat(titleFor(binding("Hades", null), current("Speedrun day"))).contains("Speedrun day — Hades");
    }

    @Test
    void sent_remembersTheTitle_andTheStreamersOwnWhenItWasChangedByHand() {
        remembered("Chill night", "Chill night — Doom");

        service.sent(user, new PlannedTitle("Speedrun day — Hades", Optional.of("Speedrun day")));

        ArgumentCaptor<StreamTitleState> saved = ArgumentCaptor.forClass(StreamTitleState.class);
        verify(stateRepository).save(saved.capture());
        assertThat(saved.getValue().getOriginalTitle()).isEqualTo("Speedrun day");
        assertThat(saved.getValue().getLastSentTitle()).isEqualTo("Speedrun day — Hades");
    }

    @Test
    void sent_keepsTheOriginal_whenTheTitleBeforeWasCatapults_orUnread() {
        remembered("Chill night", "Chill night — Doom");

        service.sent(user, new PlannedTitle("Chill night — Hades", Optional.of("Chill night — Doom")));
        service.sent(user, new PlannedTitle("Chill night — Celeste", Optional.empty()));

        ArgumentCaptor<StreamTitleState> saved = ArgumentCaptor.forClass(StreamTitleState.class);
        verify(stateRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(s -> assertThat(s.getOriginalTitle()).isEqualTo("Chill night"));
        assertThat(saved.getValue().getLastSentTitle()).isEqualTo("Chill night — Celeste");
    }

    @Test
    void sent_neverFails() {
        when(stateRepository.findById(user.getId())).thenThrow(new IllegalStateException("db down"));

        service.sent(user, new PlannedTitle("x", Optional.empty()));
    }

    @Test
    void render_onlyTheExactFormIsAVariable() {
        assertThat(StreamTitleService.render("{{GAME}} {{game}} {{ GAME }} {GAME} $1", "Doom", Optional.empty(),
                Optional.empty())).contains("Doom {{game}} {{ GAME }} {GAME} $1");
    }

    @Test
    void render_cutsToTwitchsLimit_andNeverSendsAnEmptyTitle() {
        Optional<String> none = Optional.empty();
        assertThat(StreamTitleService.render("x".repeat(150), "g", none, none))
                .hasValueSatisfying(t -> assertThat(t).hasSize(140));
        assertThat(StreamTitleService.render("  {{GAME}}  ", null, none, none)).isEmpty();
        assertThat(StreamTitleService.render("🎮".repeat(141), "g", none, none))
                .hasValueSatisfying(t -> assertThat(t.codePointCount(0, t.length())).isEqualTo(140));
    }

    @Test
    void cleaned_trims_andKeepsVariablesNotWrittenExactlyAsTyped() {
        assertThat(StreamTitleService.cleaned("  {{ORIGINAL_TITLE}} | {{game}} | {{ STREAMER }} {braces} ok "))
                .isEqualTo("{{ORIGINAL_TITLE}} | {{game}} | {{ STREAMER }} {braces} ok");
        assertThat(StreamTitleService.cleaned("   ")).isNull();
        assertThatThrownBy(() -> StreamTitleService.cleaned("x".repeat(141)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }
}
