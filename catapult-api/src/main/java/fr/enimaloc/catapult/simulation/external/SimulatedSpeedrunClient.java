package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunClient;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunGame;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRecords;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRecords.CategoryRecord;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRecords.Runner;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunRun;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * speedrun.com in the mock profile: whatever game is searched is found under that very name, with three
 * categories (the first one tied between two runners); any Twitch login or name is a user with two personal bests
 * and one run waiting for verification; any personal key is accepted.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedSpeedrunClient implements SpeedrunClient {

    private static final String GAME_ID = "simgame";
    private static final String USER_ID = "simuser";
    private static final LocalDate DATE = LocalDate.of(2026, 1, 15);

    @Override
    public List<SpeedrunGame> searchGames(String name) {
        return List.of(game(name));
    }

    @Override
    public Optional<SpeedrunRecords> records(String gameId) {
        var game = game("Simulated game");
        var url = game.url();
        return Optional.of(new SpeedrunRecords(game, List.of(
                new CategoryRecord("any", "Any%", url + "#Any", new BigDecimal("1800.500"), "realtime",
                        List.of(new Runner("Alice", "https://www.speedrun.com/users/alice"),
                                new Runner("Bob", "https://www.speedrun.com/users/bob")),
                        DATE, "https://www.speedrun.com/run/sim1", "https://www.youtube.com/watch?v=sim1"),
                new CategoryRecord("hundred", "100%", url + "#100", new BigDecimal("3600"), "realtime",
                        List.of(new Runner("Carol", "https://www.speedrun.com/users/carol")),
                        DATE, "https://www.speedrun.com/run/sim2", null),
                new CategoryRecord("low", "Low%", url + "#Low", new BigDecimal("2400.250"), "ingame",
                        List.of(new Runner("Guest", null)),
                        DATE, "https://www.speedrun.com/run/sim3", null))));
    }

    @Override
    public List<SpeedrunUser> usersByTwitch(String login) {
        return List.of(user());
    }

    @Override
    public Optional<SpeedrunUser> user(String nameOrId) {
        return Optional.of(user());
    }

    @Override
    public Optional<SpeedrunUser> profile(String apiKey) {
        return Optional.of(user());
    }

    @Override
    public List<SpeedrunRun> personalBests(String userId, String gameIdOrNull) {
        return List.of(
                new SpeedrunRun(GAME_ID, "Simulated game", "any", "Any%", 3, new BigDecimal("1850.750"), DATE,
                        Instant.parse("2026-01-15T12:00:00Z"), "https://www.speedrun.com/run/pb1"),
                new SpeedrunRun(GAME_ID, "Simulated game", "hundred", "100%", 12, new BigDecimal("3900"), DATE,
                        Instant.parse("2026-01-10T12:00:00Z"), "https://www.speedrun.com/run/pb2"));
    }

    @Override
    public List<SpeedrunRun> pendingRuns(String userId, String gameIdOrNull, String apiKeyOrNull) {
        return List.of(new SpeedrunRun(GAME_ID, "Simulated game", "low", "Low%", null, new BigDecimal("2500"),
                DATE, Instant.parse("2026-01-20T12:00:00Z"), "https://www.speedrun.com/run/pending1"));
    }

    private static SpeedrunGame game(String name) {
        return new SpeedrunGame(GAME_ID, name, name, "sim", "https://www.speedrun.com/sim", null);
    }

    private static SpeedrunUser user() {
        return new SpeedrunUser(USER_ID, "simulated", "https://www.speedrun.com/users/simulated",
                "https://www.twitch.tv/simulated");
    }
}
