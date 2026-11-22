package fr.enimaloc.catapult.service.enrichment;

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
        };
    }
}
