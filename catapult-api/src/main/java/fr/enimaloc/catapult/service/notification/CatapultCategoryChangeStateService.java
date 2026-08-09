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

    /**
     * A category change Catapult made itself, as recognised from an inbound
     * {@code channel.update}. {@code previousGameId} is nullable — there may be no
     * category to revert to (first change ever recorded for this user).
     */
    public record SelfChange(String previousGameId) {}

    /**
     * Single-use check: matches only a marker still awaiting its {@code channel.update}
     * (i.e. {@code appliedAt != null}) and clears that pending flag on a hit, so neither a
     * redelivered {@code channel.update} nor a later manual change back to the same
     * category is mistaken for a Catapult-driven one. {@code gameId}/{@code previousGameId}
     * are deliberately kept: they are what the next Catapult change reports as its
     * "previous category".
     */
    @Transactional
    public Optional<SelfChange> consumeIfMatches(UserAccount user, String observedGameId) {
        Optional<CatapultCategoryChangeState> match = repository.findById(user.getId())
                .filter(state -> state.getAppliedAt() != null)
                .filter(state -> observedGameId != null && observedGameId.equals(state.getGameId()));
        if (match.isEmpty()) {
            return Optional.empty();
        }
        CatapultCategoryChangeState state = match.get();
        state.setAppliedAt(null);
        repository.save(state);
        return Optional.of(new SelfChange(state.getPreviousGameId()));
    }
}
