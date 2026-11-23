package fr.enimaloc.catapult.service.enrichment.steamstats;

import java.time.Instant;
import java.util.Map;

/** What a member played, by Steam app id, as {@code GetOwnedGames} reports it. */
public record SteamLibrary(Map<String, Entry> games) {

    /** One owned game: minutes played overall and in the last two weeks, and when it was last played. */
    public record Entry(long playtimeMinutes, long playtimeTwoWeeksMinutes, Instant lastPlayed) {
    }
}
