package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardAppTokenService;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The Blizzard application token in the mock profile: always configured, never asks Blizzard. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedBlizzardAppTokenService extends BlizzardAppTokenService {

    public SimulatedBlizzardAppTokenService(RestClient.Builder builder, BlizzardRateLimiter limiter) {
        super(builder, limiter, "simulated", "simulated");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String token() {
        return "simulated-app-token";
    }
}
