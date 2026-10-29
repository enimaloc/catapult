package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * What a simulated platform reports for a game the control panel picked: the id the platform's real getter would
 * report, resolved by Catapult itself through {@link IgdbService} (so in the modes of {@code igdb.search} and
 * {@code igdb.game}), never typed by hand when it can be found.
 * <ul>
 *   <li>Steam: the game's Steam app id on IGDB;</li>
 *   <li>Xbox: its Microsoft Store product id on IGDB, which the Xbox getter reports once it has resolved the title;</li>
 *   <li>Minecraft: {@code minecraft} / {@code Minecraft}, all the Minecraft getter ever reports;</li>
 *   <li>Battle.net: the name, as there is no id to report (Catapult resolves it by name).</li>
 * </ul>
 */
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class GameSourceResolver {

    /**
     * @param sourceId the platform's id for the game, {@code null} when it could not be resolved
     * @param name     the name the platform reports
     * @param reason   why {@code sourceId} is missing, {@code null} when it is not
     */
    public record Resolution(@Nullable String sourceId, String name, @Nullable String reason) {}

    private final IgdbService igdb;
    private final SimulationRegistry registry;

    public GameSourceResolver(IgdbService igdb, SimulationRegistry registry) {
        this.igdb = igdb;
        this.registry = registry;
    }

    public Resolution resolve(GetterConfig.Provider provider, String name) {
        return switch (provider) {
            case MINECRAFT -> new Resolution("minecraft", "Minecraft", null);
            case BATTLENET -> new Resolution(name, name, null);
            case STEAM -> store(GameBinding.SourceType.STEAM, "Steam app id", name);
            case XBOX -> store(GameBinding.SourceType.XBOX, "Microsoft Store product id", name);
        };
    }

    private Resolution store(GameBinding.SourceType sourceType, String what, String name) {
        IgdbService.IgdbGame game = igdb.findByName(name).orElse(null);
        if (game == null) {
            return new Resolution(null, name, "IGDB found no game named " + name + simulated(SimulatedCalls.IGDB_SEARCH));
        }
        return igdb.findExternalUid(game.id(), sourceType)
                .map(uid -> new Resolution(uid, name, null))
                .orElseGet(() -> new Resolution(null, name,
                        "IGDB lists no " + what + " for " + game.name() + simulated(SimulatedCalls.IGDB_GAME)));
    }

    /** Points at the setting to change when the IGDB call that came back empty was not a real one. */
    private String simulated(CallKey key) {
        CallSetting.Mode mode = registry.setting(null, key).mode();
        return mode == CallSetting.Mode.REAL ? "" : " (" + key.id() + " is " + mode + ", not Real)";
    }
}
