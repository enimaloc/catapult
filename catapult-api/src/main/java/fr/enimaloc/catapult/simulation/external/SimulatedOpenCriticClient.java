package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticClient;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticGame;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** OpenCritic in the mock profile: whatever is searched is found, with a top critic score of 88. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedOpenCriticClient implements OpenCriticClient {

    private static final long GAME_ID = 1L;

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<Hit> search(String name) {
        return List.of(new Hit(GAME_ID, name));
    }

    @Override
    public Optional<OpenCriticGame> game(long id) {
        return Optional.of(new OpenCriticGame(id, "Simulated game", 88, 92, "Mighty", 55,
                "https://opencritic.com/game/" + id + "/simulated-game"));
    }
}
