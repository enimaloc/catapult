package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsClient;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxStatsForbiddenException;
import fr.enimaloc.catapult.service.enrichment.xboxstats.XboxTitle;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService.XstsSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Xbox Live personal stats in the mock profile: one title with a known Store Product ID (350 of 1000 gamerscore,
 * 3 of 10 achievements), and a second one whose achievements Xbox refuses (403).
 */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedXboxStatsClient implements XboxStatsClient {

    static final String TITLE_ID = "1000001";
    static final String PRODUCT_ID = "9NBLGGH4NNS1";
    static final String REFUSED_TITLE_ID = "2000002";
    private static final Instant PLAYED = Instant.parse("2026-11-20T20:00:00Z");

    @Override
    public List<XboxTitle> titleHistory(UUID userId, XstsSession session, int max, Locale locale) {
        return List.of(
                new XboxTitle(TITLE_ID, "Simulated Xbox Game", List.of(PRODUCT_ID), "Game", 3, 10, 350, 1000,
                        PLAYED),
                new XboxTitle(REFUSED_TITLE_ID, "Simulated Refused Game", List.of(), "Game", null, null, null,
                        null, PLAYED.minusSeconds(86_400)));
    }

    @Override
    public List<Achievement> achievements(UUID userId, XstsSession session, String titleId, Locale locale) {
        if (REFUSED_TITLE_ID.equals(titleId)) {
            throw new XboxStatsForbiddenException("Xbox refused the achievements of " + titleId);
        }
        if (!TITLE_ID.equals(titleId)) {
            return List.of();
        }
        return java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(i -> new Achievement("" + i, "Achievement " + i, "Do the thing " + i + ".",
                        "https://example.invalid/xbox/" + i + ".png", i <= 3,
                        i <= 3 ? PLAYED.minusSeconds(3_600L * i) : null,
                        new BigDecimal(i == 1 ? "1.5" : "20"), gamerscore(i), false))
                .toList();
    }

    /** 50 + 100 + 200 = 350 unlocked (as the title history says), and 6 × 100 + 50 locked: 1000 in all. */
    private static int gamerscore(int i) {
        return switch (i) {
            case 1, 10 -> 50;
            case 3 -> 200;
            default -> 100;
        };
    }

    @Override
    public Optional<Long> minutesPlayed(UUID userId, XstsSession session, String titleId) {
        if (REFUSED_TITLE_ID.equals(titleId)) {
            throw new XboxStatsForbiddenException("Xbox refused the stats of " + titleId);
        }
        return TITLE_ID.equals(titleId) ? Optional.of(480L) : Optional.empty();
    }
}
