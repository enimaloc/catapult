package fr.enimaloc.catapult.service.enrichment.blizzard;

/** A character of a member's WoW account, as listed by {@code /profile/user/wow}. */
public record WowUserCharacter(long id, String name, String realmSlug, String realmName, String className,
                               String faction, int level) {
}
