package fr.enimaloc.catapult.service.enrichment.hypixel;

import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.HypixelGame;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Hypixel's public data: the games played on the server now (neither retired nor legacy). Nothing about a member. */
@Schema(name = "HypixelPublisher")
public record HypixelPublisher(HypixelGame game, List<HypixelPublishedGame> games) {

    /** A Hypixel game: its type key ({@code BEDWARS}…, as the online status names it) and its clean name. */
    @Schema(name = "HypixelPublishedGame")
    public record HypixelPublishedGame(String type, String name) {
    }
}
