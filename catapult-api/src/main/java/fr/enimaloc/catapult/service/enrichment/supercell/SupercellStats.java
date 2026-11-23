package fr.enimaloc.catapult.service.enrichment.supercell;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A member's personal stats on one Supercell game, the one projection of the aggregate and of the detail route.
 * {@code status} is that of the player read ({@code ok}; {@code not_found}: no player has the declared tag any more;
 * {@code unavailable}); the game's object is null unless it is {@code ok}, and the two other games' objects are
 * always null. {@code account} is the player name, {@code tag} the player tag. What only the detail has (heroes,
 * deck, brawlers, attack / defense wins, battle count) is absent from the aggregate. {@code more} links to the detail
 * route; the service leaves it null, the API sets it ({@link #withMore}).
 */
@Schema(name = "SupercellStats")
public record SupercellStats(Status status, SupercellGame game, String account, String tag,
                             ClashOfClansStats clashOfClans, ClashRoyaleStats clashRoyale, BrawlStarsStats brawlStars,
                             String more) {

    /** These stats with the link to their detail route. */
    public SupercellStats withMore(String link) {
        return new SupercellStats(status, game, account, tag, clashOfClans, clashRoyale, brawlStars, link);
    }

    /** A clan (Clash of Clans, Clash Royale) or a club (Brawl Stars): its tag and name. */
    @Schema(name = "SupercellClan")
    public record Clan(String tag, String name) {
    }

    /**
     * Clash of Clans: the town hall (and its weapon level), experience level, trophies and best trophies, the league
     * (name and icon), war stars, the builder hall and builder base trophies, the clan; in the detail also the
     * attack and defense wins and the heroes.
     */
    @Schema(name = "SupercellClashOfClansStats")
    public record ClashOfClansStats(int townHallLevel, Integer townHallWeaponLevel, Integer expLevel, int trophies,
                                    Integer bestTrophies, String league, String leagueIconUrl, Integer warStars,
                                    Integer builderHallLevel, Integer builderBaseTrophies, Clan clan,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Integer attackWins,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Integer defenseWins,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) List<Hero> heroes) {
    }

    /** A Clash of Clans hero: its name, level, the highest level for the town hall and its village. */
    @Schema(name = "SupercellClashOfClansHero")
    public record Hero(String name, Integer level, Integer maxLevel, String village) {
    }

    /**
     * Clash Royale: experience level, trophies and best trophies, the arena, the Path of Legend league, wins,
     * losses, three-crown wins and the clan; in the detail also the battle count and the current deck.
     */
    @Schema(name = "SupercellClashRoyaleStats")
    public record ClashRoyaleStats(Integer expLevel, int trophies, Integer bestTrophies, String arena,
                                   Integer pathOfLegendLeague, Integer wins, Integer losses, Integer threeCrownWins,
                                   Clan clan,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) Integer battleCount,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) List<Card> currentDeck) {
    }

    /** A Clash Royale card of the deck: its name, level and the highest level of its rarity. */
    @Schema(name = "SupercellClashRoyaleCard")
    public record Card(String name, Integer level, Integer maxLevel) {
    }

    /**
     * Brawl Stars: experience level, trophies and highest trophies, the 3v3, solo and duo victories and the club; in
     * the detail also the brawlers.
     */
    @Schema(name = "SupercellBrawlStarsStats")
    public record BrawlStarsStats(Integer expLevel, int trophies, Integer highestTrophies, Integer trioVictories,
                                  Integer soloVictories, Integer duoVictories, Clan club,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) List<Brawler> brawlers) {
    }

    /** A brawler: its name, power level, rank, trophies and highest trophies. */
    @Schema(name = "SupercellBrawlStarsBrawler")
    public record Brawler(String name, Integer power, Integer rank, Integer trophies, Integer highestTrophies) {
    }
}
