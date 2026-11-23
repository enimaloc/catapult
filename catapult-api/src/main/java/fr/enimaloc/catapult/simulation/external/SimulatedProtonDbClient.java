package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbClient;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbSummary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** ProtonDB in the mock profile: every Steam app is a gold-tier game, nothing leaves. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedProtonDbClient implements ProtonDbClient {

    @Override
    public Optional<ProtonDbSummary> fetchSummary(String steamAppId) {
        return Optional.of(new ProtonDbSummary("gold", "gold", "platinum", "good", 0.72, 120,
                "https://www.protondb.com/app/" + steamAppId));
    }
}
