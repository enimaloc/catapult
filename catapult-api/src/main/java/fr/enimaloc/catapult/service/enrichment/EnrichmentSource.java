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
    RA_RECENT("ra-recent");

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
     * RetroAchievements sources, except the Steam schema and rarity, which are the same for everyone.
     */
    public boolean personal() {
        return switch (this) {
            case STEAM_LIBRARY, STEAM_ACHIEVEMENTS, XBOX_TITLES, XBOX_ACHIEVEMENTS, XBOX_PLAYTIME, RA_USER,
                 RA_PROGRESS, RA_RECENT -> true;
            default -> false;
        };
    }
}
