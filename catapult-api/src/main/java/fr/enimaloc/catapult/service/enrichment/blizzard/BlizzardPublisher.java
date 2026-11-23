package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;

import java.time.Instant;

/**
 * The public data of a Blizzard game; only the object of the game itself is set. {@code sc2} is reserved: always
 * null in this version.
 */
public record BlizzardPublisher(BlizzardGame game, String region, Wow wow, Hearthstone hearthstone, D3 d3,
                                Object sc2) {

    /** The WoW Token price, in gold. */
    public record Wow(long tokenGold, Instant updatedAt) {
    }

    /** The latest Hearthstone expansion that has cards. */
    public record Hearthstone(HearthstoneSet latestSet) {
    }

    /** The current Diablo III season. */
    public record D3(int season) {
    }
}
