package fr.enimaloc.catapult.service.enrichment.osu;

import fr.enimaloc.catapult.service.enrichment.osu.OsuData.Me;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.Score;
import fr.enimaloc.catapult.service.enrichment.osu.OsuData.TokenGrant;

import java.util.List;

/**
 * The osu! API v2, with the OAuth application an admin set (its client id and secret on the token requests) and the
 * member's own token on every read. Every call is charged to the {@link OsuRateLimiter}. Failures are
 * {@link OsuUnavailableException}; a refused access token is {@link OsuExceptions.AuthRefused}, a refused refresh
 * token {@link OsuExceptions.RefreshRefused}.
 */
public interface OsuClient {

    /** A new access and refresh token for {@code refreshToken} (which osu! then retires). */
    TokenGrant refresh(String refreshToken);

    /** The account {@code accessToken} belongs to, with its statistics on its default ruleset. */
    Me me(String accessToken);

    /**
     * The latest scores (passed plays only, newest first, at most {@code limit}) of the user {@code osuUserId} on
     * {@code ruleset}, read with the member's own token (scope {@code public}).
     */
    List<Score> recentScores(long osuUserId, String ruleset, int limit, String accessToken);
}
