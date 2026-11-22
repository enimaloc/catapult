package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamAchievementSchema;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamAchievementSchema.Definition;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamLibrary;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamPlayerAchievements;
import fr.enimaloc.catapult.service.enrichment.steamstats.SteamStatsClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Steam's personal stats in the mock profile: app 440 is owned (7 320 minutes) with 12 of 50 achievements
 * unlocked, the first one held by 0.4 % of the players; the Steam id {@code private} has no visible library.
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedSteamStatsClient implements SteamStatsClient {

    static final String APP_ID = "440";
    private static final int TOTAL = 50;
    private static final int UNLOCKED = 12;
    private static final Instant FIRST_UNLOCK = Instant.parse("2026-01-01T12:00:00Z");

    @Override
    public Optional<SteamLibrary> library(String steamId, String personalKeyOrNull) {
        if ("private".equals(steamId)) {
            return Optional.empty();
        }
        return Optional.of(new SteamLibrary(Map.of(APP_ID,
                new SteamLibrary.Entry(7_320, 120, Instant.parse("2026-11-20T20:00:00Z")))));
    }

    @Override
    public SteamPlayerAchievements playerAchievements(String steamId, String appId, String lang,
                                                      String personalKeyOrNull) {
        if ("private".equals(steamId)) {
            return SteamPlayerAchievements.of(Status.PRIVATE);
        }
        if (!APP_ID.equals(appId)) {
            return SteamPlayerAchievements.of(Status.NO_ACHIEVEMENTS);
        }
        Map<String, Instant> unlocked = new LinkedHashMap<>();
        for (int i = 1; i <= UNLOCKED; i++) {
            unlocked.put(apiName(i), FIRST_UNLOCK.plusSeconds(86_400L * i));
        }
        return new SteamPlayerAchievements(Status.OK, unlocked);
    }

    @Override
    public Optional<SteamAchievementSchema> schema(String appId, String lang) {
        if (!APP_ID.equals(appId)) {
            return Optional.empty();
        }
        List<Definition> all = IntStream.rangeClosed(1, TOTAL)
                .mapToObj(i -> new Definition(apiName(i), "Achievement " + i, "Do the thing " + i + ".",
                        "https://example.invalid/steam/" + i + ".jpg",
                        "https://example.invalid/steam/" + i + "_gray.jpg", i == TOTAL))
                .toList();
        return Optional.of(new SteamAchievementSchema(all));
    }

    @Override
    public Map<String, BigDecimal> globalPercentages(String appId) {
        if (!APP_ID.equals(appId)) {
            return Map.of();
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 1; i <= TOTAL; i++) {
            out.put(apiName(i), i == 1 ? new BigDecimal("0.4") : BigDecimal.valueOf(10 + i));
        }
        return out;
    }

    private static String apiName(int i) {
        return "ACH_" + i;
    }
}
