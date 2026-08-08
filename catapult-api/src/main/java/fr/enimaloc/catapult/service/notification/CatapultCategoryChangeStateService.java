package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.CatapultCategoryChangeState;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.CatapultCategoryChangeStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CatapultCategoryChangeStateService {

    private final CatapultCategoryChangeStateRepository repository;

    @Transactional
    public Optional<String> recordCatapultChangeAndReturnPrevious(UserAccount user, String newGameId) {
        CatapultCategoryChangeState state = repository.findById(user.getId()).orElseGet(() -> {
            CatapultCategoryChangeState fresh = new CatapultCategoryChangeState();
            fresh.setUser(user);
            return fresh;
        });
        String previous = state.getGameId();
        state.setPreviousGameId(previous);
        state.setGameId(newGameId);
        state.setAppliedAt(Instant.now());
        repository.save(state);
        return Optional.ofNullable(previous);
    }

    public Optional<String> matchesCatapultChange(UserAccount user, String observedGameId) {
        return repository.findById(user.getId())
                .filter(state -> observedGameId != null && observedGameId.equals(state.getGameId()))
                .map(CatapultCategoryChangeState::getPreviousGameId);
    }
}
