package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.ObsProcessCache;
import fr.enimaloc.catapult.service.ObsSession;
import fr.enimaloc.catapult.service.ProcessPredicateEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("obs.enabled")
public class ObsGameGetter implements GameGetter {

    private final ObsProcessCache obsProcessCache;
    private final ProcessBindingRepository processBindingRepository;
    private final IgdbService igdbService;
    private final ProcessPredicateEvaluator predicateEvaluator;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        ObsSession session = obsProcessCache.getSession(user);
        if (session.processes().isEmpty()) return Optional.empty();

        Set<String> names = session.processes().stream()
                .map(ObsSession.ProcessSnapshot::name)
                .collect(Collectors.toSet());

        // Step 1: bindings utilisateur (exact match + prédicats)
        List<ProcessBinding> candidates = processBindingRepository.findByUserAndProcessNameIn(user, names);
        Optional<DetectedGame> fromBinding = session.processes().stream()
                .flatMap(proc -> candidates.stream()
                        .filter(b -> b.getProcessName().equals(proc.name()))
                        .filter(b -> predicateEvaluator.evaluate(b, proc, session))
                        .findFirst()
                        .map(b -> new DetectedGame(proc.name(), GameBinding.SourceType.OBS, b.getTwitchGameName()))
                        .stream())
                .findFirst();
        if (fromBinding.isPresent()) return fromBinding;

        // Step 2: règles globales admin (regex ou exact + prédicats)
        List<ProcessBinding> globalRules = processBindingRepository.findByUserIsNull();
        if (!globalRules.isEmpty()) {
            Optional<DetectedGame> fromGlobal = matchGlobal(globalRules, session.processes().stream().toList(), session);
            if (fromGlobal.isPresent()) return fromGlobal;
        }

        // Step 3: fallback IGDB par nom d'exe
        return session.processes().stream()
                .map(proc -> igdbService.findByWindowsExecutable(proc.name())
                        .map(game -> new DetectedGame(proc.name(), GameBinding.SourceType.OBS, game.name())))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<DetectedGame> matchGlobal(
            List<ProcessBinding> globalRules,
            List<ObsSession.ProcessSnapshot> processes,
            ObsSession session) {
        return processes.stream()
                .flatMap(proc -> globalRules.stream()
                        .filter(rule -> matchesPattern(rule, proc.name()))
                        .filter(rule -> predicateEvaluator.evaluate(rule, proc, session))
                        .findFirst()
                        .map(rule -> new DetectedGame(proc.name(), GameBinding.SourceType.OBS, rule.getTwitchGameName()))
                        .stream())
                .findFirst();
    }

    private boolean matchesPattern(ProcessBinding rule, String name) {
        if (!rule.isRegex()) return rule.getProcessName().equals(name);
        return name.matches(rule.getProcessName());
    }
}
