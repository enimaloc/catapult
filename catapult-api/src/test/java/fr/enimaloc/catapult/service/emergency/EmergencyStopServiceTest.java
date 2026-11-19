package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop.Origin;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop.Scope;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.emergency.EmergencyReportRepository;
import fr.enimaloc.catapult.repository.emergency.ProviderEmergencyStopRepository;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmergencyStopServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final GetterConfig.Provider XBOX = GetterConfig.Provider.XBOX;

    private EmergencyReportRepository reportRepository;
    private ProviderEmergencyStopRepository stopRepository;
    private GetterConfigRepository getterConfigs;
    private AdminEventPublisher adminEvents;
    private EmergencyStopService service;

    private final List<EmergencyReport> savedReports = new CopyOnWriteArrayList<>();
    private final List<ProviderEmergencyStop> savedStops = new CopyOnWriteArrayList<>();
    private Instant lastGlobalLift;
    private Runnable beforeFindActive = () -> { };

    @BeforeEach
    void setUp() {
        reportRepository = mock(EmergencyReportRepository.class);
        stopRepository = mock(ProviderEmergencyStopRepository.class);
        getterConfigs = mock(GetterConfigRepository.class);
        adminEvents = mock(AdminEventPublisher.class);

        when(reportRepository.save(any(EmergencyReport.class))).thenAnswer(i -> {
            EmergencyReport r = i.getArgument(0);
            if (!savedReports.contains(r)) {
                savedReports.add(r);
            }
            return r;
        });
        when(reportRepository.findCounting(any(), any())).thenAnswer(i -> savedReports.stream()
                .filter(r -> r.getProvider() == i.getArgument(0) && r.getWithdrawnAt() == null
                        && !r.getCreatedAt().isBefore(i.<Instant>getArgument(1))).toList());
        when(reportRepository.findByChannelAndWithdrawnAtIsNull(any())).thenAnswer(i -> savedReports.stream()
                .filter(r -> r.getChannel() == i.getArgument(0) && r.getWithdrawnAt() == null).toList());
        when(stopRepository.save(any(ProviderEmergencyStop.class))).thenAnswer(i -> {
            ProviderEmergencyStop s = i.getArgument(0);
            if (!savedStops.contains(s)) {
                savedStops.add(s);
            }
            return s;
        });
        when(stopRepository.findActive(any())).thenAnswer(i -> {
            beforeFindActive.run();
            return savedStops.stream().filter(s -> s.isActive(i.getArgument(0))).toList();
        });
        when(stopRepository.findLastGlobalLiftedAt(any())).thenAnswer(i -> Optional.ofNullable(lastGlobalLift));
        when(getterConfigs.findByUserAndProvider(any(), any())).thenReturn(Optional.of(new GetterConfig()));

        service = newService();
    }

    private EmergencyStopService newService() {
        return new EmergencyStopService(reportRepository, stopRepository, getterConfigs, adminEvents,
                new SimpleMeterRegistry(), new EmergencyProperties(), TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UserAccount channel() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        return account;
    }

    private ReportOutcome report(UserAccount channel, String author) {
        return service.report(channel, XBOX, author, author, "{}", "stream", NOW.minusSeconds(600));
    }

    private ProviderEmergencyStop stop(String provider, Scope scope, UserAccount channel, Origin origin,
                                       Instant expiresAt) {
        ProviderEmergencyStop s = new ProviderEmergencyStop();
        s.setProvider(provider);
        s.setScope(scope);
        s.setChannel(channel);
        s.setOrigin(origin);
        s.setExpiresAt(expiresAt);
        savedStops.add(s);
        return s;
    }

    @Test
    void report_belowThreshold_stopsOnlyTheChannel() {
        ReportOutcome outcome = report(channel(), "a");

        assertThat(outcome.kind()).isEqualTo(ReportOutcome.Kind.CHANNEL_STOP);
        assertThat(outcome.votes()).isEqualTo(1);
        assertThat(outcome.threshold()).isEqualTo(3);
        assertThat(outcome.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(savedStops).singleElement().satisfies(s -> {
            assertThat(s.getScope()).isEqualTo(Scope.CHANNEL);
            assertThat(s.getOrigin()).isEqualTo(Origin.CHANNEL_REPORT);
        });
    }

    @Test
    void report_thirdDistinctChannelAndAuthor_stopsGlobally() {
        report(channel(), "a");
        report(channel(), "b");
        ReportOutcome outcome = report(channel(), "c");

        assertThat(outcome.kind()).isEqualTo(ReportOutcome.Kind.GLOBAL_STOP);
        assertThat(outcome.votes()).isEqualTo(3);
        assertThat(savedStops).filteredOn(s -> s.getScope() == Scope.GLOBAL).singleElement()
                .satisfies(s -> assertThat(s.getOrigin()).isEqualTo(Origin.THRESHOLD));
        verify(adminEvents).emergencyStopChanged(any());
    }

    @Test
    void report_sameModeratorOnThreeChannels_staysLocal() {
        report(channel(), "mod");
        report(channel(), "mod");
        ReportOutcome outcome = report(channel(), "mod");

        assertThat(outcome.kind()).isEqualTo(ReportOutcome.Kind.CHANNEL_STOP);
        assertThat(outcome.votes()).isEqualTo(1);
    }

    @Test
    void report_providerNotUsed_writesNothing() {
        when(getterConfigs.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());

        ReportOutcome outcome = report(channel(), "a");

        assertThat(outcome.kind()).isEqualTo(ReportOutcome.Kind.PROVIDER_NOT_USED);
        verify(reportRepository, never()).save(any());
        verify(stopRepository, never()).save(any());
    }

    @Test
    void report_duringGlobalStop_extendsIt() {
        ProviderEmergencyStop global = stop("XBOX", Scope.GLOBAL, null, Origin.THRESHOLD, NOW.plusSeconds(60));

        ReportOutcome outcome = report(channel(), "a");

        assertThat(outcome.kind()).isEqualTo(ReportOutcome.Kind.GLOBAL_EXTENDED);
        assertThat(global.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(outcome.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void report_duringOwnerStop_keepsNoExpiry() {
        ProviderEmergencyStop global = stop("XBOX", Scope.GLOBAL, null, Origin.OWNER, null);

        ReportOutcome outcome = report(channel(), "a");

        assertThat(outcome.kind()).isEqualTo(ReportOutcome.Kind.GLOBAL_EXTENDED);
        assertThat(global.getExpiresAt()).isNull();
        assertThat(outcome.expiresAt()).isNull();
    }

    @Test
    void report_countsOnlyAfterLastGlobalLift() {
        report(channel(), "a");
        report(channel(), "b");
        lastGlobalLift = NOW.minusSeconds(1);
        savedReports.forEach(r -> r.setCreatedAt(NOW.minusSeconds(60)));

        ReportOutcome outcome = report(channel(), "c");

        assertThat(outcome.kind()).isEqualTo(ReportOutcome.Kind.CHANNEL_STOP);
        assertThat(outcome.votes()).isEqualTo(1);
    }

    @Test
    void report_concurrentThirdVotes_createOneGlobalStop() throws Exception {
        report(channel(), "a");
        report(channel(), "b");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        beforeFindActive = () -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReportOutcome> first = pool.submit(() -> report(channel(), "c"));
            Future<ReportOutcome> second = pool.submit(() -> report(channel(), "d"));
            // Without the lock both threads reach findActive together; with it only one does.
            ready.await(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            go.countDown();
            List<ReportOutcome.Kind> kinds = new ArrayList<>(List.of(first.get().kind(), second.get().kind()));
            assertThat(kinds).containsExactlyInAnyOrder(
                    ReportOutcome.Kind.GLOBAL_STOP, ReportOutcome.Kind.GLOBAL_EXTENDED);
        } finally {
            pool.shutdownNow();
        }
        ArgumentCaptor<ProviderEmergencyStop> saved = ArgumentCaptor.forClass(ProviderEmergencyStop.class);
        verify(stopRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().stream().filter(s -> s.getScope() == Scope.GLOBAL).distinct())
                .hasSize(1);
        assertThat(savedStops).filteredOn(s -> s.getScope() == Scope.GLOBAL).hasSize(1);
    }

    @Test
    void ownerResume_provider_leavesAllStop() {
        UserAccount c = channel();
        ProviderEmergencyStop all = stop("ALL", Scope.GLOBAL, null, Origin.OWNER, null);
        ProviderEmergencyStop global = stop("XBOX", Scope.GLOBAL, null, Origin.OWNER, null);
        ProviderEmergencyStop local = stop("XBOX", Scope.CHANNEL, c, Origin.CHANNEL_REPORT, NOW.plusSeconds(60));
        ProviderEmergencyStop other = stop("STEAM", Scope.GLOBAL, null, Origin.OWNER, null);

        int lifted = service.ownerResume("xbox", "owner");

        assertThat(lifted).isEqualTo(2);
        assertThat(global.getLiftedAt()).isEqualTo(NOW);
        assertThat(global.getLiftedBy()).isEqualTo("owner");
        assertThat(local.getLiftedAt()).isEqualTo(NOW);
        assertThat(all.getLiftedAt()).isNull();
        assertThat(other.getLiftedAt()).isNull();
    }

    @Test
    void ownerResume_all_liftsEverything() {
        List<ProviderEmergencyStop> stops = List.of(
                stop("ALL", Scope.GLOBAL, null, Origin.OWNER, null),
                stop("XBOX", Scope.GLOBAL, null, Origin.OWNER, null),
                stop("STEAM", Scope.CHANNEL, channel(), Origin.CHANNEL_REPORT, NOW.plusSeconds(60)));

        int lifted = service.ownerResume("all", "owner");

        assertThat(lifted).isEqualTo(3);
        assertThat(stops).allSatisfy(s -> assertThat(s.getLiftedAt()).isEqualTo(NOW));
    }

    @Test
    void ownerStop_isGlobalWithoutExpiry() {
        ProviderEmergencyStop stop = service.ownerStop("all", "owner");

        assertThat(stop.getScope()).isEqualTo(Scope.GLOBAL);
        assertThat(stop.getOrigin()).isEqualTo(Origin.OWNER);
        assertThat(stop.getExpiresAt()).isNull();
        assertThat(service.isStopped(channel(), XBOX)).isTrue();
    }

    @Test
    void liftChannel_withdrawsReportsAndKeepsGlobal() {
        UserAccount c = channel();
        report(c, "a");
        ProviderEmergencyStop global = stop("XBOX", Scope.GLOBAL, null, Origin.OWNER, null);

        boolean changed = service.liftChannel(c, "streamer");

        assertThat(changed).isTrue();
        assertThat(savedReports).singleElement().satisfies(r -> assertThat(r.getWithdrawnAt()).isEqualTo(NOW));
        assertThat(savedStops).filteredOn(s -> s.getScope() == Scope.CHANNEL)
                .singleElement().satisfies(s -> assertThat(s.getLiftedAt()).isEqualTo(NOW));
        assertThat(global.getLiftedAt()).isNull();
    }

    @Test
    void isStopped_allStopCoversEveryProvider() {
        stop("ALL", Scope.GLOBAL, null, Origin.OWNER, null);

        for (GetterConfig.Provider provider : GetterConfig.Provider.values()) {
            assertThat(service.isStopped(channel(), provider)).isTrue();
        }
    }

    @Test
    void isStopped_channelStopOnlyForThatChannel() {
        UserAccount c = channel();
        stop("XBOX", Scope.CHANNEL, c, Origin.CHANNEL_REPORT, NOW.plusSeconds(60));

        assertThat(service.isStopped(c, XBOX)).isTrue();
        assertThat(service.isStopped(channel(), XBOX)).isFalse();
        assertThat(service.isStopped(c, GetterConfig.Provider.STEAM)).isFalse();
        assertThat(service.activeStopsFor(channel())).isEmpty();
        assertThat(service.activeStopsFor(c)).hasSize(1);
    }
}
