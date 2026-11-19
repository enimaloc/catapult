package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.common.dto.emergency.ChannelEmergencyDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyReportDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop.Origin;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop.Scope;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.emergency.EmergencyReportRepository;
import fr.enimaloc.catapult.repository.emergency.ProviderEmergencyStopRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmergencyViewsTest {

    private final ProviderEmergencyStopRepository stops = mock(ProviderEmergencyStopRepository.class);
    private final EmergencyReportRepository reports = mock(EmergencyReportRepository.class);
    private final EmergencyVodResolver vods = mock(EmergencyVodResolver.class);
    private final EmergencyStopService stopService = mock(EmergencyStopService.class);
    private final GetterConfigRepository getterConfigs = mock(GetterConfigRepository.class);
    private final EmergencyViews views = new EmergencyViews(stops, reports, vods, stopService, getterConfigs);

    private static UserAccount account(String login) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername(login);
        return user;
    }

    private static ProviderEmergencyStop stop(String provider, Scope scope, UserAccount channel) {
        ProviderEmergencyStop stop = new ProviderEmergencyStop();
        stop.setId(UUID.randomUUID());
        stop.setProvider(provider);
        stop.setScope(scope);
        stop.setChannel(channel);
        stop.setOrigin(scope == Scope.GLOBAL ? Origin.OWNER : Origin.CHANNEL_REPORT);
        return stop;
    }

    private static EmergencyReport report(UserAccount channel) {
        EmergencyReport report = new EmergencyReport();
        report.setId(UUID.randomUUID());
        report.setChannel(channel);
        report.setProvider(GetterConfig.Provider.STEAM);
        report.setReportedByLogin("viewer");
        report.setContext("{\"game\":\"x\"}");
        return report;
    }

    @Test
    void stops_active_mapsTheChannelLoginOfALocalStop() {
        UserAccount channel = account("streamer");
        when(stops.findActiveWithChannel(any())).thenReturn(List.of(
                stop("STEAM", Scope.CHANNEL, channel), stop("ALL", Scope.GLOBAL, null)));

        List<EmergencyStopDto> out = views.stops(true);

        assertThat(out).extracting(EmergencyStopDto::channelLogin).containsExactly("streamer", null);
        assertThat(out).extracting(EmergencyStopDto::scope).containsExactly("CHANNEL", "GLOBAL");
        assertThat(out.get(1).origin()).isEqualTo("OWNER");
    }

    @Test
    void stops_inactive_readsTheHistory() {
        ProviderEmergencyStop lifted = stop("STEAM", Scope.GLOBAL, null);
        lifted.setLiftedAt(Instant.parse("2026-11-19T10:00:00Z"));
        lifted.setLiftedBy("owner");
        when(stops.findInactiveWithChannel(any(), any(Pageable.class))).thenReturn(List.of(lifted));

        assertThat(views.stops(false)).singleElement().satisfies(dto -> {
            assertThat(dto.liftedBy()).isEqualTo("owner");
            assertThat(dto.liftedAt()).isEqualTo(lifted.getLiftedAt());
        });
    }

    @Test
    void reports_aFailingVodLookupOnlyLeavesThatVodOut() {
        UserAccount channel = account("streamer");
        EmergencyReport ok = report(channel);
        EmergencyReport broken = report(channel);
        broken.setWithdrawnAt(Instant.now());
        when(reports.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ok, broken)));
        when(vods.vodUrl(ok)).thenReturn(Optional.of("https://www.twitch.tv/videos/1?t=0h1m0s"));
        when(vods.vodUrl(broken)).thenThrow(new IllegalStateException("twitch down"));

        List<EmergencyReportDto> out = views.reports(0);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).vodUrl()).isEqualTo("https://www.twitch.tv/videos/1?t=0h1m0s");
        assertThat(out.get(0).channelLogin()).isEqualTo("streamer");
        assertThat(out.get(0).contextJson()).isEqualTo("{\"game\":\"x\"}");
        assertThat(out.get(0).withdrawn()).isFalse();
        assertThat(out.get(1).vodUrl()).isNull();
        assertThat(out.get(1).withdrawn()).isTrue();
    }

    @Test
    void channel_keepsTheGlobalStopsOfUsedProvidersAndAnyAllStop() {
        UserAccount channel = account("streamer");
        channel.setBotEnabled(true);
        GetterConfig steam = new GetterConfig();
        steam.setProvider(GetterConfig.Provider.STEAM);
        steam.setEnabled(true);
        GetterConfig xbox = new GetterConfig();
        xbox.setProvider(GetterConfig.Provider.XBOX);
        xbox.setEnabled(false);
        when(getterConfigs.findByUserOrderByPriorityAsc(channel)).thenReturn(List.of(steam, xbox));
        ProviderEmergencyStop own = stop("STEAM", Scope.CHANNEL, channel);
        when(stopService.activeStopsFor(channel)).thenReturn(List.of(own, stop("STEAM", Scope.GLOBAL, null),
                stop("XBOX", Scope.GLOBAL, null), stop("ALL", Scope.GLOBAL, null)));

        ChannelEmergencyDto out = views.channel(channel);

        assertThat(out.botEnabled()).isTrue();
        assertThat(out.channelStops()).extracting(EmergencyStopDto::channelLogin).containsExactly("streamer");
        assertThat(out.globalStopsOnUsedProviders()).extracting(EmergencyStopDto::provider)
                .containsExactly("STEAM", "ALL");
    }

    @Test
    void status_onlyExposesTheGlobalStops() {
        when(stopService.activeStops()).thenReturn(List.of(
                stop("STEAM", Scope.CHANNEL, account("streamer")), stop("XBOX", Scope.GLOBAL, null)));

        assertThat(views.status().globalStops()).singleElement().satisfies(dto -> {
            assertThat(dto.provider()).isEqualTo("XBOX");
            assertThat(dto.channelLogin()).isNull();
        });
    }
}
