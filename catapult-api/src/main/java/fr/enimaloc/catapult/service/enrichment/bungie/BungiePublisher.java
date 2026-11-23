package fr.enimaloc.catapult.service.enrichment.bungie;

import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BungieGame;

import java.time.Instant;

/** The public data of a Bungie game: the Destiny 2 season in progress. Nothing here is about a member. */
public record BungiePublisher(BungieGame game, Season season) {

    /** A season: its number, name, and when it starts and ends (as Bungie announces them). */
    public record Season(int number, String name, Instant startsAt, Instant endsAt) {
    }
}
