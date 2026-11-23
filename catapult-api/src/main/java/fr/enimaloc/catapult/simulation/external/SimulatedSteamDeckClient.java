package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckClient;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The Steam Deck report in the mock profile: every Steam app is verified, nothing leaves. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedSteamDeckClient implements SteamDeckClient {

    @Override
    public Optional<SteamDeckCompatibility> fetch(String steamAppId) {
        return Optional.of(new SteamDeckCompatibility(SteamDeckCompatibility.Category.VERIFIED, List.of(
                new SteamDeckCompatibility.TestedItem("4",
                        "#SteamDeckVerified_TestResult_ControllerGlyphsMatchDeckDevice"))));
    }
}
