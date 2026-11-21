package fr.enimaloc.catapult.service.enrichment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Settings of the game enrichment sources; per-source values are keyed by {@link EnrichmentSource#key()}. */
@Component
@ConfigurationProperties("catapult.enrichment")
@Getter
@Setter
public class EnrichmentProperties {

    /** Resolved settings of one source. */
    public record Source(boolean enabled, Duration ttl) {
    }

    /** Resolved settings of the HowLongToBeat scraper. */
    public record Hltb(boolean scrapeEnabled, Duration scrapeInterval, int breakerThreshold, Duration breakerPause) {
    }

    /** Overrides of one source; an unset field falls back to the source's default. */
    @Getter
    @Setter
    public static class SourceSettings {
        private Boolean enabled;
        private Duration ttl;
    }

    /** Overrides of the HowLongToBeat scraper. */
    @Getter
    @Setter
    public static class HltbSettings {
        private boolean scrapeEnabled = true;
        private Duration scrapeInterval = Duration.ofSeconds(2);
        private int breakerThreshold = 5;
        private Duration breakerPause = Duration.ofMinutes(30);
    }

    /** Per-source overrides, keyed by source key. */
    private Map<String, SourceSettings> sources = new HashMap<>();
    /** How long a "known absent" answer is trusted. */
    private Duration absentTtl = Duration.ofDays(1);
    /** Longest the aggregate endpoint waits for the slow sources. */
    private Duration aggregateTimeout = Duration.ofSeconds(2);
    /** Upstream calls one client IP may trigger per minute. */
    private int ipBudgetPerMinute = 10;
    private HltbSettings hltb = new HltbSettings();
    /** Concurrent ffmpeg processes for trailer extraction. */
    private int ffmpegMaxProcesses = 4;
    /** Longest a single ffmpeg process may run. */
    private Duration ffmpegMaxDuration = Duration.ofMinutes(10);

    public Source source(EnrichmentSource source) {
        SourceSettings s = sources.get(source.key());
        boolean enabled = s == null || s.getEnabled() == null || s.getEnabled();
        Duration ttl = s != null && s.getTtl() != null ? s.getTtl() : defaultTtl(source);
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

    public Hltb hltb() {
        return new Hltb(hltb.isScrapeEnabled(), hltb.getScrapeInterval(), hltb.getBreakerThreshold(),
                hltb.getBreakerPause());
    }

    public int ffmpegMaxProcesses() {
        return ffmpegMaxProcesses;
    }

    public Duration ffmpegMaxDuration() {
        return ffmpegMaxDuration;
    }

    private static Duration defaultTtl(EnrichmentSource source) {
        return switch (source) {
            case HLTB, PCGAMINGWIKI -> Duration.ofDays(7);
            case STEAMGRIDDB -> Duration.ofDays(30);
            case PROTONDB, STEAMDECK, TRAILER -> Duration.ofDays(1);
        };
    }
}
