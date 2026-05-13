package fr.enimaloc.catapult.getter;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.service.ObsProcessCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ObsGameGetter implements GameGetter {

    private final ObsProcessCache obsProcessCache;
    private final ProcessBindingRepository processBindingRepository;

    @Override
    public Optional<DetectedGame> getCurrentGame(UserAccount user) {
        Set<String> processes = obsProcessCache.getProcesses(user);
        if (processes.isEmpty()) return Optional.empty();
        return processBindingRepository.findFirstByUserAndProcessNameIn(user, processes)
                .map(pb -> new DetectedGame(pb.getProcessName(), GameBinding.SourceType.OBS, pb.getTwitchGameName()));
    }
}
