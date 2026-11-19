package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import fr.enimaloc.catapult.repository.emergency.EmergencyReportRepository;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Finds the VOD of the stream a report was made during, after the fact: Twitch only archives it later. */
@Slf4j
@Service
public class EmergencyVodResolver {

    private final EmergencyReportRepository reports;
    private final TwitchChatService twitch;
    private final EmergencyProperties properties;
    private final SiteRole siteRole;
    private final Clock clock;

    @Autowired
    public EmergencyVodResolver(EmergencyReportRepository reports, TwitchChatService twitch,
                                EmergencyProperties properties, SiteRole siteRole) {
        this(reports, twitch, properties, siteRole, Clock.systemUTC());
    }

    EmergencyVodResolver(EmergencyReportRepository reports, TwitchChatService twitch,
                         EmergencyProperties properties, SiteRole siteRole, Clock clock) {
        this.reports = reports;
        this.twitch = twitch;
        this.properties = properties;
        this.siteRole = siteRole;
        this.clock = clock;
    }

    /**
     * The VOD link at the moment of the report, rewound a little; empty without a stream or an already resolved VOD.
     * Only reads: the VOD is looked up by {@link #resolvePending()}, never while a page is built.
     */
    public Optional<String> vodUrl(EmergencyReport report) {
        if (report.getStreamId() == null || report.getStreamStartedAt() == null || report.getVodId() == null) {
            return Optional.empty();
        }

        Duration offset = Duration.between(report.getStreamStartedAt(), report.getCreatedAt())
                .minus(properties.getVodRewind());
        if (offset.isNegative()) offset = Duration.ZERO;
        return Optional.of("https://www.twitch.tv/videos/" + report.getVodId() + "?t="
                + offset.toHours() + "h" + offset.toMinutesPart() + "m" + offset.toSecondsPart() + "s");
    }

    @Scheduled(fixedDelayString = "PT15M")
    @Transactional
    public void resolvePending() {
        if (!siteRole.isActive()) return;
        Instant resolveSince = clock.instant().minus(properties.getVodResolveMaxAge());
        for (EmergencyReport report : reports.findByVodIdIsNullAndStreamIdIsNotNullAndCreatedAtAfter(resolveSince)) {
            try {
                resolve(report);
            } catch (Exception e) {
                log.warn("[Emergency] Could not resolve the VOD of report {}: {}", report.getId(), e.getMessage());
            }
        }
        Instant cutoff = clock.instant().minus(properties.getReportRetention());
        long purged = reports.deleteByCreatedAtBefore(cutoff);
        if (purged > 0) log.info("[Emergency] Purged {} reports older than {}", purged, cutoff);
    }

    private void resolve(EmergencyReport report) {
        twitch.archiveVideoId(report.getChannel(), report.getStreamId()).ifPresent(vodId -> {
            report.setVodId(vodId);
            reports.save(report);
        });
    }
}
