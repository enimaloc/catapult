package fr.enimaloc.catapult.service.enrichment;

/** The external sources a game can be enriched from. */
public enum EnrichmentSource {
    HLTB("hltb"),
    PROTONDB("protondb"),
    STEAMGRIDDB("steamgriddb"),
    STEAMDECK("steamdeck"),
    PCGAMINGWIKI("pcgamingwiki"),
    TRAILER("trailer"),
    PRICES("prices"),
    OPENCRITIC("opencritic"),
    TWITCH_CATEGORY("twitch-category"),
    TWITCH_LIVES("twitch-lives"),
    TWITCH_CLIPS("twitch-clips"),
    SPEEDRUN_GAME("speedrun-game"),
    SPEEDRUN_RECORDS("speedrun-records"),
    SPEEDRUN_USER("speedrun-user"),
    SPEEDRUN_PBS("speedrun-pbs"),
    SPEEDRUN_PENDING("speedrun-pending"),
    STEAM_LIBRARY("steam-library"),
    STEAM_ACHIEVEMENTS("steam-achievements"),
    STEAM_SCHEMA("steam-schema"),
    STEAM_RARITY("steam-rarity"),
    XBOX_TITLES("xbox-titles"),
    XBOX_ACHIEVEMENTS("xbox-achievements"),
    XBOX_PLAYTIME("xbox-playtime"),
    RA_USER("ra-user"),
    RA_PROGRESS("ra-progress"),
    RA_RECENT("ra-recent"),
    BLIZZARD_WOW_CHARACTER("blizzard-wow-character"),
    BLIZZARD_WOW_ACHIEVEMENTS("blizzard-wow-achievements"),
    BLIZZARD_SC2_PROFILE("blizzard-sc2-profile"),
    BLIZZARD_D3_PROFILE("blizzard-d3-profile"),
    BLIZZARD_WOW_TOKEN("blizzard-wow-token"),
    BLIZZARD_HS_SETS("blizzard-hs-sets"),
    BLIZZARD_HS_DECK("blizzard-hs-deck"),
    BLIZZARD_D3_SEASON("blizzard-d3-season");

    private final String key;

    EnrichmentSource(String key) {
        this.key = key;
    }

    /** Stable identifier used in the cache table and in the property names. */
    public String key() {
        return key;
    }

    /**
     * Whether the source holds one member's own stats (keyed {@code u:<userId>...}): the Steam, Xbox and
     * RetroAchievements sources and the Blizzard character, achievement and profile ones, except the Steam schema and rarity, which are the same for everyone.
     */
    public boolean personal() {
        return switch (this) {
            case STEAM_LIBRARY, STEAM_ACHIEVEMENTS, XBOX_TITLES, XBOX_ACHIEVEMENTS, XBOX_PLAYTIME, RA_USER,
                 RA_PROGRESS, RA_RECENT, BLIZZARD_WOW_CHARACTER, BLIZZARD_WOW_ACHIEVEMENTS, BLIZZARD_SC2_PROFILE,
                 BLIZZARD_D3_PROFILE -> true;
            default -> false;
        };
    }

    /**
     * Whether a failed fetch of the source is remembered for a while (a per-key backoff) and concurrent fetches of
     * one key share a single upstream call: the plain public lookups that no service retries or limits on its own.
     */
    public boolean backsOff() {
        return switch (this) {
            case PROTONDB, STEAMDECK, PCGAMINGWIKI -> true;
            default -> false;
        };
    }
}
