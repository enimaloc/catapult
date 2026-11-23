package fr.enimaloc.catapult.service.enrichment.supercell;

import java.util.List;

/**
 * What the enrichment keeps of a Supercell player (the cache shape; never served as is). One of {@code clashOfClans},
 * {@code clashRoyale} and {@code brawlStars} is set: that of the game asked.
 */
public final class SupercellModels {

    private SupercellModels() {
    }

    /** A player of one game: their tag and name, and that game's numbers. */
    public record SupercellPlayer(String tag, String name, CocPlayer clashOfClans, CrPlayer clashRoyale,
                                  BsPlayer brawlStars) {
    }

    /** A clan (Clash of Clans, Clash Royale) or a club (Brawl Stars). */
    public record Group(String tag, String name) {
    }

    /** Clash of Clans: the home village, the builder base, the league and the heroes. */
    public record CocPlayer(int townHallLevel, Integer townHallWeaponLevel, Integer expLevel, int trophies,
                            Integer bestTrophies, Integer warStars, Integer attackWins, Integer defenseWins,
                            Integer builderHallLevel, Integer builderBaseTrophies, String league, String leagueIconUrl,
                            Group clan, List<Hero> heroes) {
    }

    /** A Clash of Clans hero: its name, level and the highest level for the town hall, and its village. */
    public record Hero(String name, Integer level, Integer maxLevel, String village) {
    }

    /** Clash Royale: trophies, the arena, battles and the current deck. */
    public record CrPlayer(Integer expLevel, int trophies, Integer bestTrophies, Integer wins, Integer losses,
                           Integer threeCrownWins, Integer battleCount, String arena, Integer pathOfLegendLeague,
                           Group clan, List<Card> currentDeck) {
    }

    /** A Clash Royale card of the deck: its name, level and the highest level of its rarity. */
    public record Card(String name, Integer level, Integer maxLevel) {
    }

    /** Brawl Stars: trophies, victories per mode, the club and the brawlers. */
    public record BsPlayer(Integer expLevel, int trophies, Integer highestTrophies, Integer trioVictories,
                           Integer soloVictories, Integer duoVictories, Group club, List<Brawler> brawlers) {
    }

    /** A brawler: its name, power level, rank and trophies. */
    public record Brawler(String name, Integer power, Integer rank, Integer trophies, Integer highestTrophies) {
    }
}
