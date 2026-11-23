package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Settings of the game enrichment sources; each source has its own {@code catapult.enrichment.<key>.*} block. */
@Component
@ConfigurationProperties("catapult.enrichment")
@Getter
@Setter
public class EnrichmentProperties {

    /** Resolved settings of one source. */
    public record Source(boolean enabled, Duration ttl) {
    }

    /** Overrides of one source; an unset field falls back to the source's default. */
    @Getter
    @Setter
    public static class SourceSettings {
        private Boolean enabled;
        private Duration ttl;
        /** Prices only: shops left out of the comparison ({@code catapult.enrichment.prices.excluded-shops}). */
        private List<String> excludedShops = new ArrayList<>();
        /** OpenCritic only: calls allowed per UTC day ({@code catapult.enrichment.opencritic.daily-cap}). */
        private int dailyCap = 50;
    }

    /** Speedrun.com settings that are not a source's own ({@code catapult.enrichment.speedrun.*}). */
    @Getter
    @Setter
    public static class Speedrun {
        /** Upstream calls per minute across the speedrun.com sources. */
        private int perMinute = 60;
    }

    /** RetroAchievements settings that are not a source's own ({@code catapult.enrichment.retroachievements.*}). */
    @Getter
    @Setter
    public static class RetroAchievements {
        /** Upstream calls per minute. */
        private int perMinute = 30;
    }

    /** Xbox stats settings that are not a source's own ({@code catapult.enrichment.xbox-stats.*}). */
    @Getter
    @Setter
    public static class XboxStats {
        /** Upstream calls per minute across all members. */
        private int perMinute = 120;
        /** Upstream calls per minute one member may trigger. */
        private int perMemberPerMinute = 10;
    }

    /** Blizzard API settings that are not a source's own ({@code catapult.enrichment.blizzard.*}). */
    @Getter
    @Setter
    public static class Blizzard {
        /** Upstream calls per hour (the API allows 36 000). */
        private int perHour = 30_000;
        /** Upstream calls per second (the API allows 100). */
        private int perSecond = 20;
        /** Region used when the member's account or the request names none. */
        private BlizzardRegion defaultRegion = BlizzardRegion.EU;
    }

    /** Bungie.net API settings that are not a source's own ({@code catapult.enrichment.bungie.*}). */
    @Getter
    @Setter
    public static class Bungie {
        /** Upstream calls per hour: Bungie documents no limit, this one stays well under what it is known to allow. */
        private int perHour = 30_000;
        /** Upstream calls per second (Bungie is known to throttle above about 25). */
        private int perSecond = 20;
    }

    /**
     * Riot API settings that are not a source's own ({@code catapult.enrichment.riot.*}). The rates are the
     * personal key's (20 / s, 100 / 2 min) until Riot's {@code X-App-Rate-Limit} header says otherwise. The IGDB ids
     * tell the Riot games apart; an empty one maps no game.
     */
    @Getter
    @Setter
    public static class Riot {
        /** The whole Riot source; it also stays off while the key pool is empty. */
        private boolean enabled = true;
        /** Calls per second, per key and cluster, before a header is read. */
        private int perSecond = 20;
        /** Calls per two minutes, per key and cluster, before a header is read. */
        private int perTwoMinutes = 100;
        private String lolIgdbId = "115";
        private String tftIgdbId = "";
        private String valorantIgdbId = "126459";
    }

    /**
     * FACEIT Data API settings that are not a source's own ({@code catapult.enrichment.faceit.*}). The gateway was
     * seen answering {@code ratelimit-limit: 20, 20;w=1}: half of it is used. The hourly cap is a choice (FACEIT
     * documents none). The IGDB id tells Counter-Strike 2 (an empty one maps it by its Steam app id only).
     */
    @Getter
    @Setter
    public static class Faceit {
        /** The whole FACEIT source; it also stays off while the key pool is empty. */
        private boolean enabled = true;
        /** Calls per second (spaced: a call too close to the previous one waits). */
        private int perSecond = 10;
        /** Calls per hour (refused beyond). */
        private int perHour = 10_000;
        private String cs2IgdbId = "242408";
    }

    private SourceSettings hltb = new SourceSettings();
    private SourceSettings protondb = new SourceSettings();
    private SourceSettings steamgriddb = new SourceSettings();
    private SourceSettings steamdeck = new SourceSettings();
    private SourceSettings pcgamingwiki = new SourceSettings();
    private SourceSettings trailer = new SourceSettings();
    private SourceSettings prices = new SourceSettings();
    private SourceSettings opencritic = new SourceSettings();
    private SourceSettings twitchCategory = new SourceSettings();
    private SourceSettings twitchLives = new SourceSettings();
    private SourceSettings twitchClips = new SourceSettings();
    private SourceSettings speedrunGame = new SourceSettings();
    private SourceSettings speedrunRecords = new SourceSettings();
    private SourceSettings speedrunUser = new SourceSettings();
    private SourceSettings speedrunPbs = new SourceSettings();
    private SourceSettings speedrunPending = new SourceSettings();
    private SourceSettings steamLibrary = new SourceSettings();
    private SourceSettings steamAchievements = new SourceSettings();
    private SourceSettings steamSchema = new SourceSettings();
    private SourceSettings steamRarity = new SourceSettings();
    private SourceSettings xboxTitles = new SourceSettings();
    private SourceSettings xboxAchievements = new SourceSettings();
    private SourceSettings xboxPlaytime = new SourceSettings();
    private SourceSettings raUser = new SourceSettings();
    private SourceSettings raProgress = new SourceSettings();
    private SourceSettings raRecent = new SourceSettings();
    private SourceSettings blizzardWowCharacter = new SourceSettings();
    private SourceSettings blizzardWowAchievements = new SourceSettings();
    private SourceSettings blizzardSc2Profile = new SourceSettings();
    private SourceSettings blizzardD3Profile = new SourceSettings();
    private SourceSettings blizzardWowToken = new SourceSettings();
    private SourceSettings blizzardHsSets = new SourceSettings();
    private SourceSettings blizzardHsDeck = new SourceSettings();
    private SourceSettings blizzardD3Season = new SourceSettings();
    private SourceSettings riotLolSummoner = new SourceSettings();
    private SourceSettings riotLolLeague = new SourceSettings();
    private SourceSettings riotLolLive = new SourceSettings();
    private SourceSettings riotTftLeague = new SourceSettings();
    private SourceSettings riotLolPatch = new SourceSettings();
    private SourceSettings riotLolChampions = new SourceSettings();
    private Blizzard blizzard = new Blizzard();
    private SourceSettings bungieProfile = new SourceSettings();
    private SourceSettings bungieTriumphs = new SourceSettings();
    private SourceSettings bungieDefinitions = new SourceSettings();
    private SourceSettings bungieSeason = new SourceSettings();
    private Bungie bungie = new Bungie();
    private Riot riot = new Riot();
    private Faceit faceit = new Faceit();
    private Speedrun speedrun = new Speedrun();
    private RetroAchievements retroachievements = new RetroAchievements();
    private XboxStats xboxStats = new XboxStats();
    /** Upstream calls one member may trigger per minute through the personal stats routes. */
    private int memberPerMinute = 20;
    /** How long a "known absent" answer of a personal source is trusted. */
    private Duration personalAbsentTtl = Duration.ofMinutes(15);
    /** How long a "known absent" answer is trusted. */
    private Duration absentTtl = Duration.ofDays(1);
    /** Longest the aggregate endpoint waits for the slow sources. */
    private Duration aggregateTimeout = Duration.ofSeconds(2);
    /** Upstream calls one client IP may trigger per minute. */
    private int ipBudgetPerMinute = 10;
    /**
     * Concurrent ffmpeg processes for trailer extraction. One slot is always kept for the authenticated route, so
     * public callers share the rest: with 1, public trailer streams are never served.
     */
    private int ffmpegMaxProcesses = 4;
    /** Longest a single ffmpeg process may run. */
    private Duration ffmpegMaxDuration = Duration.ofMinutes(10);

    public Source source(EnrichmentSource source) {
        SourceSettings s = settingsOf(source);
        boolean enabled = s.getEnabled() == null || s.getEnabled();
        Duration ttl = s.getTtl() != null ? s.getTtl() : defaultTtl(source);
        return new Source(enabled, ttl);
    }

    public Duration absentTtl() {
        return absentTtl;
    }

    public Duration aggregateTimeout() {
        return aggregateTimeout;
    }

    public int ipBudgetPerMinute() {
        return ipBudgetPerMinute;
    }

    public int ffmpegMaxProcesses() {
        return ffmpegMaxProcesses;
    }

    public Duration ffmpegMaxDuration() {
        return ffmpegMaxDuration;
    }

    public Duration personalAbsentTtl() {
        return personalAbsentTtl;
    }

    public int memberPerMinute() {
        return memberPerMinute;
    }

    public int raPerMinute() {
        return retroachievements.getPerMinute();
    }

    public int xboxStatsPerMinute() {
        return xboxStats.getPerMinute();
    }

    public int xboxStatsPerMemberPerMinute() {
        return xboxStats.getPerMemberPerMinute();
    }

    public int blizzardPerHour() {
        return blizzard.getPerHour();
    }

    public int blizzardPerSecond() {
        return blizzard.getPerSecond();
    }

    public BlizzardRegion blizzardDefaultRegion() {
        return blizzard.getDefaultRegion();
    }

    public int bungiePerHour() {
        return bungie.getPerHour();
    }

    public int bungiePerSecond() {
        return bungie.getPerSecond();
    }

    public boolean riotEnabled() {
        return riot.isEnabled();
    }

    public boolean faceitEnabled() {
        return faceit.isEnabled();
    }

    public int speedrunPerMinute() {
        return speedrun.getPerMinute();
    }

    public List<String> excludedShops() {
        return prices.getExcludedShops();
    }

    public int openCriticDailyCap() {
        return opencritic.getDailyCap();
    }

    private SourceSettings settingsOf(EnrichmentSource source) {
        return switch (source) {
            case HLTB -> hltb;
            case PROTONDB -> protondb;
            case STEAMGRIDDB -> steamgriddb;
            case STEAMDECK -> steamdeck;
            case PCGAMINGWIKI -> pcgamingwiki;
            case TRAILER -> trailer;
            case PRICES -> prices;
            case OPENCRITIC -> opencritic;
            case TWITCH_CATEGORY -> twitchCategory;
            case TWITCH_LIVES -> twitchLives;
            case TWITCH_CLIPS -> twitchClips;
            case SPEEDRUN_GAME -> speedrunGame;
            case SPEEDRUN_RECORDS -> speedrunRecords;
            case SPEEDRUN_USER -> speedrunUser;
            case SPEEDRUN_PBS -> speedrunPbs;
            case SPEEDRUN_PENDING -> speedrunPending;
            case STEAM_LIBRARY -> steamLibrary;
            case STEAM_ACHIEVEMENTS -> steamAchievements;
            case STEAM_SCHEMA -> steamSchema;
            case STEAM_RARITY -> steamRarity;
            case XBOX_TITLES -> xboxTitles;
            case XBOX_ACHIEVEMENTS -> xboxAchievements;
            case XBOX_PLAYTIME -> xboxPlaytime;
            case RA_USER -> raUser;
            case RA_PROGRESS -> raProgress;
            case RA_RECENT -> raRecent;
            case BLIZZARD_WOW_CHARACTER -> blizzardWowCharacter;
            case BLIZZARD_WOW_ACHIEVEMENTS -> blizzardWowAchievements;
            case BLIZZARD_SC2_PROFILE -> blizzardSc2Profile;
            case BLIZZARD_D3_PROFILE -> blizzardD3Profile;
            case BLIZZARD_WOW_TOKEN -> blizzardWowToken;
            case BLIZZARD_HS_SETS -> blizzardHsSets;
            case BLIZZARD_HS_DECK -> blizzardHsDeck;
            case BLIZZARD_D3_SEASON -> blizzardD3Season;
            case BUNGIE_PROFILE -> bungieProfile;
            case BUNGIE_TRIUMPHS -> bungieTriumphs;
            case BUNGIE_DEFINITIONS -> bungieDefinitions;
            case BUNGIE_SEASON -> bungieSeason;
            case RIOT_LOL_SUMMONER -> riotLolSummoner;
            case RIOT_LOL_LEAGUE -> riotLolLeague;
            case RIOT_LOL_LIVE -> riotLolLive;
            case RIOT_TFT_LEAGUE -> riotTftLeague;
            case RIOT_LOL_PATCH -> riotLolPatch;
            case RIOT_LOL_CHAMPIONS -> riotLolChampions;
        };
    }

    private static Duration defaultTtl(EnrichmentSource source) {
        return switch (source) {
            case HLTB, PCGAMINGWIKI -> Duration.ofDays(7);
            case STEAMGRIDDB -> Duration.ofDays(30);
            case PROTONDB, STEAMDECK, TRAILER, OPENCRITIC, TWITCH_CATEGORY -> Duration.ofDays(1);
            case PRICES -> Duration.ofHours(6);
            case TWITCH_LIVES -> Duration.ofMinutes(5);
            case TWITCH_CLIPS, SPEEDRUN_RECORDS -> Duration.ofHours(1);
            case SPEEDRUN_GAME -> Duration.ofDays(30);
            case SPEEDRUN_USER -> Duration.ofDays(7);
            case SPEEDRUN_PBS, SPEEDRUN_PENDING, STEAM_LIBRARY, XBOX_PLAYTIME -> Duration.ofMinutes(15);
            case STEAM_ACHIEVEMENTS, XBOX_TITLES, XBOX_ACHIEVEMENTS, RA_PROGRESS, RA_RECENT -> Duration.ofMinutes(5);
            case STEAM_SCHEMA -> Duration.ofDays(7);
            case STEAM_RARITY -> Duration.ofDays(1);
            case RA_USER -> Duration.ofMinutes(2);
            case BLIZZARD_WOW_CHARACTER, BLIZZARD_SC2_PROFILE -> Duration.ofMinutes(10);
            case BLIZZARD_WOW_ACHIEVEMENTS -> Duration.ofMinutes(30);
            case BLIZZARD_D3_PROFILE -> Duration.ofMinutes(15);
            case BLIZZARD_WOW_TOKEN -> Duration.ofMinutes(20);
            case BLIZZARD_HS_SETS, BLIZZARD_D3_SEASON -> Duration.ofDays(1);
            case BLIZZARD_HS_DECK -> Duration.ofDays(7);
            case BUNGIE_PROFILE -> Duration.ofMinutes(2);
            case BUNGIE_TRIUMPHS -> Duration.ofMinutes(30);
            case BUNGIE_DEFINITIONS, BUNGIE_SEASON -> Duration.ofDays(1);
            case RIOT_LOL_SUMMONER, RIOT_LOL_LEAGUE, RIOT_TFT_LEAGUE -> Duration.ofMinutes(10);
            case RIOT_LOL_LIVE -> Duration.ofMinutes(1);
            case RIOT_LOL_PATCH -> Duration.ofHours(1);
            case RIOT_LOL_CHAMPIONS -> Duration.ofDays(1);
        };
    }
}
