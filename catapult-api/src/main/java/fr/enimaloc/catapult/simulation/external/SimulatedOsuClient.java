package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.osu.OsuClient;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * osu! in the mock profile: any refresh token is renewed, every token reads the osu! account {@link #USER_ID}
 * ({@code Simulated}, osu!standard by default, ranked), whose latest pass is an S on a simulated map. The osu! source
 * still needs an application set in the admin Keys tab (any well-formed values).
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedOsuClient implements OsuClient {

    public static final long USER_ID = 4_242_424L;
    private static final Instant WHEN = Instant.parse("2026-11-20T18:00:00Z");

    @Override
    public OsuData.TokenGrant refresh(String refreshToken) {
        return new OsuData.TokenGrant("simulated-access", Instant.now().plus(Duration.ofHours(24)),
                "simulated-refresh");
    }

    @Override
    public OsuData.Me me(String accessToken) {
        OsuData.Statistics standard = new OsuData.Statistics(4_321.5, 12_345, 678, 98.76, 23_456L, 1_234_567L, 101,
                42.0, 1_234, 9_876_543_210L, true);
        OsuData.Statistics taiko = new OsuData.Statistics(150.0, null, null, 91.2, 120L, 3_600L, 20, 10.0, 300,
                1_000_000L, false);
        return new OsuData.Me(USER_ID, "Simulated", "osu", "FR", standard,
                Map.of("osu", standard, "taiko", taiko));
    }

    @Override
    public List<OsuData.Score> recentScores(long osuUserId, String ruleset, int limit, String accessToken) {
        return List.of(
                new OsuData.Score(9_001L, ruleset, "S", 256.4, 97.5, 812, List.of("HD"), true, WHEN,
                        new OsuData.Beatmap(1_001L, "Simulated Song", "Simulated Artist", "Insane", 5.42,
                                "https://osu.ppy.sh/b/1001")),
                new OsuData.Score(9_000L, ruleset, "A", 180.0, 93.1, 640, List.of(), true, WHEN.minusSeconds(240),
                        new OsuData.Beatmap(1_000L, "Another Song", "Simulated Artist", "Hard", 4.1,
                                "https://osu.ppy.sh/b/1000"))).subList(0, Math.min(limit, 2));
    }
}
