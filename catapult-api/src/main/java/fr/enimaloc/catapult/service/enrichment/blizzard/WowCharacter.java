package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.time.Instant;

/** A World of Warcraft character: profile, Mythic+ rating of the season (null when unrated) and avatar. */
public record WowCharacter(long id, String name, String realmSlug, String realmName, int level, String className,
                           String specName, String raceName, String faction, String guild, int achievementPoints,
                           Integer averageItemLevel, Integer equippedItemLevel, Instant lastLogin,
                           Double mythicRating, String avatarUrl) {
}
