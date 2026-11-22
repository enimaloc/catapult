package fr.enimaloc.catapult.service.enrichment.bungie;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;

import java.time.Instant;
import java.util.List;

/**
 * A member's personal stats on a Bungie game (Destiny 2), the one projection of the aggregate and of the detail
 * route. {@code status} is that of {@code destiny2}. {@code account} is the Bungie Name ({@code Name#1234}).
 * {@code destiny2.characters} is filled in the detail only (absent from the aggregate). {@code more} links to the
 * detail route; the service leaves it null, the API sets it ({@link #withMore}).
 */
public record BungieStats(Status status, BungieGame game, String account, Destiny2Stats destiny2, String more) {

    /** These stats with the link to their detail route. */
    public BungieStats withMore(String link) {
        return new BungieStats(status, game, account, destiny2, link);
    }

    /**
     * The Destiny 2 profile; everything but {@code status} is null unless it is ok. {@code platform} is the
     * platform the profile is read from ({@code steam}, {@code xbox}, {@code psn}, {@code epic}…), {@code character}
     * the character played last, {@code activity} the one under way (null when none). {@code triumphScore} is null
     * when Bungie withholds it or could not be read in time.
     */
    public record Destiny2Stats(Status status, String platform, Integer guardianRank, Integer triumphScore,
                                Instant lastPlayed, Character character, Activity activity,
                                @JsonInclude(JsonInclude.Include.NON_NULL) List<Character> characters) {

        /** Only a status. */
        public static Destiny2Stats of(Status status) {
            return new Destiny2Stats(status, null, null, null, null, null, null, null);
        }
    }

    /** A character: its class ({@code titan}, {@code hunter}, {@code warlock}), light level, time played. */
    public record Character(String className, int light, long minutesPlayed, Instant lastPlayed, String emblemUrl) {
    }

    /** The activity under way; its names are null when the manifest could not be read in time. */
    public record Activity(String name, String mode, Instant startedAt) {
    }
}
