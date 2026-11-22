package fr.enimaloc.catapult.service.enrichment.xboxstats;

import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService.XstsSession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads of the member's Xbox Live data under their XSTS session. Every request is charged to the member's budget of
 * the {@link XboxStatsRateLimiter}; a failure to answer is an {@link XboxStatsUnavailableException}.
 */
public interface XboxStatsClient {

    /** The member's title history (one page of at most {@code max} titles), with Titlehub's achievement summaries. */
    List<XboxTitle> titleHistory(UUID userId, XstsSession session, int max);

    /**
     * The member's achievements of a title (contract 2, one page). Empty for a title without any (or an Xbox 360
     * one).
     *
     * @throws XboxStatsForbiddenException when Xbox answers 401 or 403
     */
    List<Achievement> achievements(UUID userId, XstsSession session, String titleId);

    /**
     * Minutes played of a title; empty when the stat is absent.
     *
     * @throws XboxStatsForbiddenException when Xbox answers 401 or 403
     */
    Optional<Long> minutesPlayed(UUID userId, XstsSession session, String titleId);
}
