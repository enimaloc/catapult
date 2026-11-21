package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbAssets.Asset;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** SteamGridDB in the mock profile: every game has a grid and a hero, answered without a key from the pool. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedSteamGridDbClient implements SteamGridDbClient {

    private static final long GAME_ID = 1L;

    @Override
    public Optional<SteamGridDbAssets> fetchBySteamAppId(String steamAppId) {
        return Optional.of(assets("Simulated game " + steamAppId));
    }

    @Override
    public Optional<SteamGridDbAssets> fetchByName(String name) {
        return Optional.of(assets(name));
    }

    @Override
    public Optional<SteamGridDbAssets> fetchByGameId(long gameId, String name) {
        return Optional.of(assets(name == null ? "Simulated game " + gameId : name));
    }

    private static SteamGridDbAssets assets(String name) {
        Asset grid = new Asset("https://cdn2.steamgriddb.com/grid/simulated.png",
                "https://cdn2.steamgriddb.com/thumb/simulated.jpg", 600, 900, "alternate");
        Asset hero = new Asset("https://cdn2.steamgriddb.com/hero/simulated.png",
                "https://cdn2.steamgriddb.com/thumb/simulated-hero.jpg", 1920, 620, "alternate");
        return new SteamGridDbAssets(GAME_ID, name, "https://www.steamgriddb.com/game/" + GAME_ID,
                List.of(grid), List.of(hero), List.of(), List.of());
    }
}
