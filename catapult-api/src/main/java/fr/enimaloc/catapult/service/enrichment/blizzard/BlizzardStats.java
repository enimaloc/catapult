package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.service.enrichment.publisher.PublisherGames.BlizzardGame;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Achievement;
import fr.enimaloc.catapult.service.enrichment.steamstats.PersonalStats.Status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A member's personal stats on one Blizzard game, the one projection of the aggregate and of the detail route.
 * {@code status} is that of the game's own object ({@code wow}, {@code sc2} or {@code d3}); the other two are null.
 * {@code characters}, {@code ladder} and {@code heroes} are filled in the detail only.
 */
public record BlizzardStats(Status status, BlizzardGame game, String account, String region, WowStats wow,
                            Sc2Stats sc2, D3Stats d3) {

    /** The main World of Warcraft character; everything but {@code status} is null unless the status is ok. */
    public record WowStats(Status status, String name, String realm, Integer level, String className,
                           String specName, String race, String faction, String guild, Integer achievementPoints,
                           Integer averageItemLevel, Integer equippedItemLevel, Instant lastLogin,
                           Double mythicRating, String avatarUrl, List<Achievement> recentAchievements,
                           List<Character> characters) {

        /** An imported character, without any internal id. */
        public record Character(String region, String name, String realm, int level, String className,
                                String faction, boolean main) {
        }
    }

    /** A StarCraft II profile. */
    public record Sc2Stats(Status status, String name, String clanName, String clanTag, String portrait,
                           Integer swarmLevel, Integer achievementPoints, Map<String, Sc2Profile.Mode> modes,
                           List<Sc2LadderEntry> ladder) {
    }

    /** A Diablo III profile. */
    public record D3Stats(Status status, String battleTag, Integer paragonLevel, Integer paragonLevelSeason,
                          Integer paragonLevelHardcore, String guild, Long monstersKilled, Long elitesKilled,
                          D3Hero lastHero, List<D3Hero> heroes) {
    }
}
