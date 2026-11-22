package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.rastats.RaAchievement;
import fr.enimaloc.catapult.service.enrichment.rastats.RaCredentials;
import fr.enimaloc.catapult.service.enrichment.rastats.RaGameProgress;
import fr.enimaloc.catapult.service.enrichment.rastats.RaKeyRejectedException;
import fr.enimaloc.catapult.service.enrichment.rastats.RaRecentGame;
import fr.enimaloc.catapult.service.enrichment.rastats.RaUser;
import fr.enimaloc.catapult.service.enrichment.rastats.RetroAchievementsClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * RetroAchievements in the mock profile: the user {@code simulated} played "Super Mario World" two minutes ago
 * (10 of 40 achievements, 8 of them in hardcore). The key {@code valid} is accepted, any other is rejected.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedRetroAchievementsClient implements RetroAchievementsClient {

    static final String USER = "simulated";
    static final String ULID = "01J00000000000000000000SIM";
    static final int GAME_ID = 228;
    private static final String TITLE = "Super Mario World";
    private static final String CONSOLE = "SNES/Super Famicom";
    private static final int TOTAL = 40;
    private static final int EARNED = 10;
    private static final int HARDCORE = 8;

    private final Clock clock;

    @Autowired
    public SimulatedRetroAchievementsClient() {
        this(Clock.systemUTC());
    }

    SimulatedRetroAchievementsClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<RaUser> profile(String userOrUlid, RaCredentials credentials) {
        check(credentials);
        if (!USER.equals(userOrUlid) && !ULID.equals(userOrUlid)) {
            return Optional.empty();
        }
        return Optional.of(new RaUser(USER, ULID, "https://example.invalid/ra/simulated.png", 1200, 300, 2400,
                "Playing " + TITLE, GAME_ID, false));
    }

    @Override
    public List<RaRecentGame> recentlyPlayed(String ulid, int count, RaCredentials credentials) {
        check(credentials);
        if (!ULID.equals(ulid) || count < 1) {
            return List.of();
        }
        return List.of(new RaRecentGame(GAME_ID, CONSOLE, TITLE, "https://example.invalid/ra/smw.png",
                clock.instant().minus(Duration.ofMinutes(2)), TOTAL, EARNED, HARDCORE, 100, 80, 400));
    }

    @Override
    public Optional<RaGameProgress> progress(int gameId, String ulid, RaCredentials credentials) {
        check(credentials);
        if (gameId != GAME_ID) {
            return Optional.empty();
        }
        Instant base = clock.instant().minus(Duration.ofDays(2));
        List<RaAchievement> all = IntStream.rangeClosed(1, TOTAL)
                .mapToObj(i -> new RaAchievement(1000 + i, "Achievement " + i, "Do the thing " + i + ".", 10, 20,
                        "https://example.invalid/ra/badge" + i + ".png", i, null, 5000 - i * 10, 2000 - i * 5,
                        i <= EARNED ? base.plusSeconds(600L * i) : null,
                        i <= HARDCORE ? base.plusSeconds(600L * i) : null, null, null))
                .toList();
        return Optional.of(new RaGameProgress(GAME_ID, TITLE, CONSOLE, "https://example.invalid/ra/smw.png", TOTAL,
                6000, 2500, EARNED, HARDCORE, "25.00%", "20.00%", 120, null, null, all));
    }

    @Override
    public List<RaAchievement> recentAchievements(String ulid, int minutes, RaCredentials credentials) {
        check(credentials);
        return List.of();
    }

    private static void check(RaCredentials credentials) {
        if (credentials == null || !"valid".equals(credentials.key())) {
            throw new RaKeyRejectedException();
        }
    }
}
