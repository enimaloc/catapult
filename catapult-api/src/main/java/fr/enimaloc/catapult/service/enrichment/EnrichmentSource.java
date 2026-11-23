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
    BLIZZARD_D3_SEASON("blizzard-d3-season"),
    BUNGIE_PROFILE("bungie-profile"),
    BUNGIE_TRIUMPHS("bungie-triumphs"),
    BUNGIE_DEFINITIONS("bungie-definitions"),
    BUNGIE_SEASON("bungie-season"),
    RIOT_LOL_SUMMONER("riot-lol-summoner"),
    RIOT_LOL_LEAGUE("riot-lol-league"),
    RIOT_LOL_LIVE("riot-lol-live"),
    RIOT_TFT_LEAGUE("riot-tft-league"),
    RIOT_LOL_PATCH("riot-lol-patch"),
    RIOT_LOL_CHAMPIONS("riot-lol-champions"),
    FACEIT_PLAYER("faceit-player"),
    FACEIT_STATS("faceit-stats"),
    FACEIT_LAST_MATCH("faceit-last-match"),
    CHESS_CHESSCOM_STATS("chess-chesscom-stats"),
    CHESS_CHESSCOM_GAMES("chess-chesscom-games"),
    CHESS_LICHESS_USER("chess-lichess-user"),
    OSU_PROFILE("osu-profile"),
    OSU_RECENT("osu-recent");

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
     * RetroAchievements sources, the Blizzard character, achievement and profile ones, the Bungie profile and
     * triumphs, the Riot summoner, league and live game ones, the FACEIT ones, the Chess.com and Lichess ones and
     * the osu! profile and recent scores, except the Steam schema and rarity, which are the same for everyone.
     */
    public boolean personal() {
        return switch (this) {
            case STEAM_LIBRARY, STEAM_ACHIEVEMENTS, XBOX_TITLES, XBOX_ACHIEVEMENTS, XBOX_PLAYTIME, RA_USER,
                 RA_PROGRESS, RA_RECENT, BLIZZARD_WOW_CHARACTER, BLIZZARD_WOW_ACHIEVEMENTS, BLIZZARD_SC2_PROFILE,
                 BLIZZARD_D3_PROFILE, BUNGIE_PROFILE, BUNGIE_TRIUMPHS -> true;
            case RIOT_LOL_SUMMONER, RIOT_LOL_LEAGUE, RIOT_LOL_LIVE, RIOT_TFT_LEAGUE -> true;
            case FACEIT_PLAYER, FACEIT_STATS, FACEIT_LAST_MATCH -> true;
            case CHESS_CHESSCOM_STATS, CHESS_CHESSCOM_GAMES, CHESS_LICHESS_USER -> true;
            case OSU_PROFILE, OSU_RECENT -> true;
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
