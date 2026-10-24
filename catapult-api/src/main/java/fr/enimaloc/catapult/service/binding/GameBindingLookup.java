package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class GameBindingLookup {

    private final GameBindingRepository repository;

    /**
     * Read-only counterpart to {@link BindingService#resolveOrCreate}: the existing binding for a
     * detected game, or empty when there is none. Creates and mutates nothing, so callers that
     * merely describe the current state (notifications, read views) can't cause a write.
     */
    public Optional<GameBinding> findByDetectedGame(UserAccount user, DetectedGame detectedGame) {
        return repository.findByUserAndSourceIdAndSourceType(
            user, detectedGame.getSourceId(), detectedGame.getSourceType());
    }
}
