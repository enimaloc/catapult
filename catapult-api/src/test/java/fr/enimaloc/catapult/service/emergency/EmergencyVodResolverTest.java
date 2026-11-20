package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import fr.enimaloc.catapult.repository.emergency.EmergencyReportRepository;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergencyVodResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-19T14:00:00Z");

    private final EmergencyReportRepository reports = mock(EmergencyReportRepository.class);
    private final TwitchChatService twitch = mock(TwitchChatService.class);
    private final SiteRole siteRole = mock(SiteRole.class);
    private final EmergencyProperties properties = new EmergencyProperties();
    private final EmergencyVodResolver resolver = new EmergencyVodResolver(
            reports, twitch, properties, siteRole, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UserAccount channel = new UserAccount();

    EmergencyVodResolverTest() {
        channel.setId(UUID.randomUUID());
        when(siteRole.isActive()).thenReturn(true);
    }

    private EmergencyReport report(String reportedAt) {
        EmergencyReport r = new EmergencyReport();
        r.setChannel(channel);
        r.setStreamId("s-1");
        r.setStreamStartedAt(Instant.parse("2026-11-19T12:00:00Z"));
        r.setCreatedAt(Instant.parse(reportedAt));
        return r;
    }

    @Test
    void vodUrl_rewindsTwoMinutes() {
        EmergencyReport r = report("2026-11-19T13:25:30Z");
        r.setVodId("v-7");

        assertThat(resolver.vodUrl(r)).contains("https://www.twitch.tv/videos/v-7?t=1h23m30s");
        verify(twitch, never()).archiveVideoId(any(), any());
    }

    @Test
    void vodUrl_clampsAtZero() {
        EmergencyReport r = report("2026-11-19T12:01:00Z");
        r.setVodId("v-7");

        assertThat(resolver.vodUrl(r)).contains("https://www.twitch.tv/videos/v-7?t=0h0m0s");
        verify(twitch, never()).archiveVideoId(any(), any());
    }

    @Test
    void vodUrl_unresolvedVod_isEmptyAndAsksNothingOfTwitch() {
        EmergencyReport r = report("2026-11-19T13:00:00Z");

        assertThat(resolver.vodUrl(r)).isEmpty();
        verify(twitch, never()).archiveVideoId(any(), any());
    }

    @Test
    void vodUrl_noStream_isEmpty() {
        EmergencyReport r = report("2026-11-19T13:00:00Z");
        r.setStreamId(null);

        assertThat(resolver.vodUrl(r)).isEmpty();
        verify(twitch, never()).archiveVideoId(any(), any());
    }

    @Test
    void resolvePending_resolvesAndPurges() {
        EmergencyReport r = report("2026-11-19T13:00:00Z");
        when(reports.findByVodIdIsNullAndStreamIdIsNotNullAndCreatedAtAfter(any())).thenReturn(List.of(r));
        when(twitch.archiveVideoId(channel, "s-1")).thenReturn(Optional.of("v-1"));

        resolver.resolvePending();

        assertThat(r.getVodId()).isEqualTo("v-1");
        verify(reports).findByVodIdIsNullAndStreamIdIsNotNullAndCreatedAtAfter(NOW.minus(properties.getVodResolveMaxAge()));
        verify(reports).deleteByCreatedAtBefore(NOW.minus(properties.getReportRetention()));
    }

    @Test
    void resolvePending_onStandby_doesNothing() {
        when(siteRole.isActive()).thenReturn(false);

        resolver.resolvePending();

        verify(reports, never()).deleteByCreatedAtBefore(any());
    }
}
