package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.ObsProcessCache;
import fr.enimaloc.catapult.service.ObsSession;
import fr.enimaloc.catapult.service.ProcessPredicateEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObsGameGetterTest {

    @Mock private ObsProcessCache obsProcessCache;
    @Mock private ProcessBindingRepository processBindingRepository;
    @Mock private IgdbService igdbService;
    @Mock private ProcessPredicateEvaluator predicateEvaluator;

    @InjectMocks private ObsGameGetter getter;

    private UserAccount user;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getCurrentGame_noProcesses_returnsEmpty() {
        when(obsProcessCache.getSession(user)).thenReturn(ObsSession.EMPTY);

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_processesWithNoBinding_returnsEmpty() {
        when(obsProcessCache.getSession(user)).thenReturn(session("unknown", null));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
        when(igdbService.findByWindowsExecutable("unknown")).thenReturn(Optional.empty());

        assertThat(getter.getCurrentGame(user)).isEmpty();
    }

    @Test
    void getCurrentGame_noBindingButIgdbExeMatch_returnsDetectedGame() {
        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
        when(igdbService.findByWindowsExecutable("hl2"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("51", "Half-Life 2")));

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("hl2");
        assertThat(result.get().getSourceType()).isEqualTo(GameBinding.SourceType.OBS);
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    }

    @Test
    void getCurrentGame_matchingBinding_noPredicate_returnsDetectedGame() {
        ProcessBinding binding = binding("hl2", "Half-Life 2");

        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of(binding));
        when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceId()).isEqualTo("hl2");
        assertThat(result.get().getSourceType()).isEqualTo(GameBinding.SourceType.OBS);
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    }

    @Test
    void getCurrentGame_matchingBinding_predicateFails_fallsBackToIgdb() {
        ProcessBinding binding = binding("hl2", "Half-Life 2");
        ProcessPredicate pred = new ProcessPredicate();
        pred.setType(ProcessPredicate.PredicateType.WORKING_DIRECTORY);
        pred.setValue("C:\\Games\\HL2");
        pred.setOsTarget(ProcessPredicate.OsTarget.ALL);
        binding.getPredicates().add(pred);

        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", "C:\\Other"));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of(binding));
        when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(false);
        when(igdbService.findByWindowsExecutable("hl2"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("51", "Half-Life 2")));

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    }

    @Test
    void getCurrentGame_matchingBinding_predicatePasses_returnsBinding() {
        ProcessBinding binding = binding("hl2", "Half-Life 2");
        ProcessPredicate pred = new ProcessPredicate();
        pred.setType(ProcessPredicate.PredicateType.WORKING_DIRECTORY);
        pred.setValue("C:\\Games\\HL2");
        pred.setOsTarget(ProcessPredicate.OsTarget.ALL);
        binding.getPredicates().add(pred);

        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", "C:\\Games\\HL2"));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of(binding));
        when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ObsSession session(String processName, String workingDirectory) {
        return new ObsSession("WINDOWS", Map.of(),
                Set.of(new ObsSession.ProcessSnapshot(processName, workingDirectory, null)));
    }

    private static ProcessBinding binding(String processName, String gameName) {
        ProcessBinding pb = new ProcessBinding();
        pb.setProcessName(processName);
        pb.setTwitchGameId("70");
        pb.setTwitchGameName(gameName);
        return pb;
    }

    @Test
    void getCurrentGame_globalRuleMatchesExact_whenNoUserBinding() {
        ProcessBinding globalRule = globalBinding("hl2", "Half-Life 2");

        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
        when(processBindingRepository.findByUserIsNull()).thenReturn(List.of(globalRule));
        when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
        assertThat(result.get().getSourceType()).isEqualTo(GameBinding.SourceType.OBS);
    }

    @Test
    void getCurrentGame_globalRuleMatchesRegex_whenNoUserBinding() {
        ProcessBinding globalRule = globalBinding("hl.*", "Half-Life");
        globalRule.setRegex(true);

        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
        when(processBindingRepository.findByUserIsNull()).thenReturn(List.of(globalRule));
        when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life");
    }

    @Test
    void getCurrentGame_userBindingTakesPriorityOverGlobalRule() {
        ProcessBinding userBinding = binding("hl2", "Half-Life 2 User");

        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of(userBinding));
        when(predicateEvaluator.evaluate(any(), any(), any())).thenReturn(true);

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2 User");
    }

    @Test
    void getCurrentGame_globalRuleRegexNoMatch_fallsBackToIgdb() {
        ProcessBinding globalRule = globalBinding("minecraft.*", "Minecraft");
        globalRule.setRegex(true);

        when(obsProcessCache.getSession(user)).thenReturn(session("hl2", null));
        when(processBindingRepository.findByUserAndProcessNameIn(any(), any())).thenReturn(List.of());
        when(processBindingRepository.findByUserIsNull()).thenReturn(List.of(globalRule));
        when(igdbService.findByWindowsExecutable("hl2"))
                .thenReturn(Optional.of(new IgdbService.IgdbGame("51", "Half-Life 2")));

        Optional<DetectedGame> result = getter.getCurrentGame(user);

        assertThat(result).isPresent();
        assertThat(result.get().getSourceName()).isEqualTo("Half-Life 2");
    }

    private static ProcessBinding globalBinding(String pattern, String gameName) {
        ProcessBinding pb = new ProcessBinding();
        pb.setProcessName(pattern);
        pb.setTwitchGameId("70");
        pb.setTwitchGameName(gameName);
        return pb;
    }
}
