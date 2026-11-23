package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.chess.ChessClient;
import fr.enimaloc.catapult.service.enrichment.chess.ChessNotFoundException;
import fr.enimaloc.catapult.service.enrichment.chess.ChessRating;
import fr.enimaloc.catapult.service.enrichment.chess.ChessUnavailableException;
import fr.enimaloc.catapult.service.enrichment.chess.ChesscomDailyGame;
import fr.enimaloc.catapult.service.enrichment.chess.ChesscomPlayer;
import fr.enimaloc.catapult.service.enrichment.chess.ChesscomStats;
import fr.enimaloc.catapult.service.enrichment.chess.LichessUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chess.com and Lichess in the mock profile: any name is a player (Chess.com lowercases it), except {@code gone},
 * which does not exist, and {@code down}, whose site is out of reach. Each player has two daily games on Chess.com
 * (one waiting for their move) and is playing a game on Lichess.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedChessClient implements ChessClient {

    private static final Instant WHEN = Instant.parse("2026-11-20T18:00:00Z");

    @Override
    public ChesscomPlayer chesscomPlayer(String username) {
        String name = known(username);
        return new ChesscomPlayer(Math.abs(name.hashCode()) + 1L, name, "https://www.chess.com/member/" + name, null,
                null);
    }

    @Override
    public ChesscomStats chesscomStats(String username) {
        known(username);
        return new ChesscomStats(Map.of(
                "rapid", new ChessRating(1_512, 45, null, null, 1_600, 120, 60, 50, 10, WHEN),
                "blitz", new ChessRating(1_430, 60, null, null, 1_480, 300, 150, 130, 20, WHEN),
                "daily", new ChessRating(1_650, 90, null, null, 1_700, 40, 25, 12, 3, WHEN)), null);
    }

    @Override
    public List<ChesscomDailyGame> chesscomGames(String username) {
        known(username);
        return List.of(
                new ChesscomDailyGame("https://www.chess.com/game/daily/1000000001", "white", "simulatedrival", true,
                        WHEN.plusSeconds(86_400), "chess"),
                new ChesscomDailyGame("https://www.chess.com/game/daily/1000000002", "black", "anotherrival", false,
                        WHEN.plusSeconds(43_200), "chess"));
    }

    @Override
    public LichessUser lichessUser(String username) {
        String name = known(username);
        return new LichessUser(name, username.strip(), null, "https://lichess.org/@/" + username.strip(), Map.of(
                "blitz", new ChessRating(1_720, 60, false, 12, null, 800, null, null, null, null),
                "rapid", new ChessRating(1_810, 75, true, -5, null, 40, null, null, null, null)),
                840, 420, 380, 40, "https://lichess.org/SimGame1/white", WHEN);
    }

    private static String known(String username) {
        String name = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "gone" -> throw new ChessNotFoundException("no such player");
            case "down" -> throw new ChessUnavailableException("simulated outage");
            default -> name;
        };
    }
}
