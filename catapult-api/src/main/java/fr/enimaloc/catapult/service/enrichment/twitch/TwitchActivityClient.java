package fr.enimaloc.catapult.service.enrichment.twitch;

import java.time.Instant;
import java.util.Optional;

/** Twitch Helix, as far as the category activity needs it. Failures throw, so they are never cached. */
public interface TwitchActivityClient {

    /** False when Twitch is not configured: nothing can be fetched. */
    boolean isConfigured();

    Optional<TwitchCategory> categoryByIgdbId(String igdbId);

    Optional<TwitchCategory> categoryById(String id);

    /** The lives of {@code gameId}, filtered by {@code language} when not null. */
    TwitchLives lives(String gameId, String language);

    /** The most viewed clips of {@code gameId} created since {@code since}. */
    TwitchClips clips(String gameId, Instant since);
}
