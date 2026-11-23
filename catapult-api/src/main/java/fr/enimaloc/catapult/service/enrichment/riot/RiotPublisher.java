package fr.enimaloc.catapult.service.enrichment.riot;

import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.RiotGame;
import io.swagger.v3.oas.annotations.media.Schema;

/** The public data of a Riot game; only the object of the game itself is set. Nothing about a member. */
public record RiotPublisher(RiotGame game, Lol lol) {

    /** League of Legends: the current patch (Data Dragon version). */
    @Schema(name = "RiotPublisherLol")
    public record Lol(String patch) {
    }
}
