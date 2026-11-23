package fr.enimaloc.catapult.service.enrichment.blizzard;

import java.util.List;

/**
 * The Blizzard web API. Every call is charged to the {@link BlizzardRateLimiter}. Failures are
 * {@link BlizzardUnavailableException}; only the recognised JSON "not found" bodies are
 * {@link BlizzardNotFoundException}.
 */
public interface BlizzardClient {

    /** Profile, Mythic+ profile and media of a character (3 requests); the name is lowercased here. */
    WowCharacter wowCharacter(BlizzardRegion region, String realmSlug, String name, String locale);

    WowAchievements wowAchievements(BlizzardRegion region, String realmSlug, String name, String locale);

    /** The Battle.net account id {@code userAccessToken} belongs to (never kept), from the OAuth userinfo. */
    long userAccountId(String userAccessToken);

    /**
     * The characters of the account that {@code userAccessToken} belongs to (never kept), named in {@code locale};
     * empty on a 404.
     */
    List<WowUserCharacter> wowUserCharacters(BlizzardRegion region, String userAccessToken, String locale);

    /** The StarCraft II profiles of a Battle.net account, from the default region's host. */
    List<Sc2ProfileRef> sc2Profiles(long bnetAccountId);

    /** A profile, with its ladder summary (one more request) when {@code detail}. */
    Sc2Profile sc2Profile(BlizzardRegion host, Sc2ProfileRef ref, String locale, boolean detail);

    D3Profile d3Profile(BlizzardRegion region, String battleTag, String locale);

    WowTokenPrice wowToken(BlizzardRegion region);

    List<HearthstoneSet> hearthstoneSets(String locale);

    HearthstoneDeck hearthstoneDeck(String code, String locale);

    int d3CurrentSeason(BlizzardRegion region);
}
