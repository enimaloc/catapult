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
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides provider emergency stops: a report stops the provider on the reporting channel at once,
 * and instance-wide once enough distinct channels and authors reported it. The owner stops and
 * resumes directly. Reads of active stops go through a short cache, invalidated by every write.
 */
@Service
@Slf4j
public class EmergencyStopService {

    static final String ALL = "ALL";
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final EmergencyReportRepository reports;
    private final ProviderEmergencyStopRepository stops;
    private final GetterConfigRepository getterConfigs;
    private final AdminEventPublisher adminEvents;
    private final MeterRegistry meters;
    private final EmergencyProperties properties;
    private final TransactionOperations tx;
    private final Clock clock;

    private final Map<GetterConfig.Provider, Object> locks = new ConcurrentHashMap<>();
    private volatile List<ProviderEmergencyStop> cached;
    private volatile Instant cachedAt;

    @Autowired
    public EmergencyStopService(EmergencyReportRepository reports, ProviderEmergencyStopRepository stops,
                                GetterConfigRepository getterConfigs, AdminEventPublisher adminEvents,
                                MeterRegistry meters, EmergencyProperties properties,
                                PlatformTransactionManager transactionManager) {
        this(reports, stops, getterConfigs, adminEvents, meters, properties,
                requiresNew(transactionManager), Clock.systemUTC());
    }

    EmergencyStopService(EmergencyReportRepository reports, ProviderEmergencyStopRepository stops,
                         GetterConfigRepository getterConfigs, AdminEventPublisher adminEvents,
                         MeterRegistry meters, EmergencyProperties properties,
                         TransactionOperations tx, Clock clock) {
        this.reports = reports;
        this.stops = stops;
        this.getterConfigs = getterConfigs;
        this.adminEvents = adminEvents;
        this.meters = meters;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** Whether the provider is stopped for the channel, by an {@code ALL} or provider stop, global or local. */
    public boolean isStopped(UserAccount channel, GetterConfig.Provider provider) {
        return cachedActive().stream().anyMatch(s -> covers(s, channel, provider));
    }

    /** Whether the channel has the provider enabled: only then a report of it means anything. */
    public boolean isProviderUsed(UserAccount channel, GetterConfig.Provider provider) {
        return getterConfigs.findByUserAndProvider(channel, provider).map(GetterConfig::isEnabled).orElse(false);
    }

    /**
     * Records a report. The vote counting and the global decision run under a per-provider lock,
     * outside the transaction that writes them, so a second report sees the first one committed.
     * That transaction is always a new one (REQUIRES_NEW), so the commit still happens before the
     * lock is released even when the caller is already inside a transaction.
     * <p>
     * Votes are the size of a maximum matching of authors to channels, so one author on several
     * channels counts once. While a global stop is active it is extended only when that quorum is
     * reached again; a lone report leaves it untouched ({@code GLOBAL_ACTIVE}).
     */
    public ReportOutcome report(UserAccount channel, GetterConfig.Provider provider, String reporterTwitchId,
                                String reporterLogin, String contextJson, String streamId,
                                Instant streamStartedAt) {
        int threshold = properties.getThreshold();
        if (!isProviderUsed(channel, provider)) {
            return new ReportOutcome(ReportOutcome.Kind.PROVIDER_NOT_USED, 0, threshold, null);
        }
        synchronized (locks.computeIfAbsent(provider, p -> new Object())) {
            try {
                return tx.execute(status -> decide(channel, provider, reporterTwitchId, reporterLogin,
                        contextJson, streamId, streamStartedAt));
            } finally {
                invalidate();
            }
        }
    }

    private ReportOutcome decide(UserAccount channel, GetterConfig.Provider provider, String reporterTwitchId,
                                 String reporterLogin, String contextJson, String streamId,
                                 Instant streamStartedAt) {
        Instant now = clock.instant();
        int threshold = properties.getThreshold();
        String providerName = provider.name();

        EmergencyReport report = new EmergencyReport();
        report.setChannel(channel);
        report.setProvider(provider);
        report.setReportedByTwitchId(reporterTwitchId);
        report.setReportedByLogin(reporterLogin);
        report.setCreatedAt(now);
        report.setContext(contextJson);
        report.setStreamId(streamId);
        report.setStreamStartedAt(streamStartedAt);
        reports.save(report);

        List<ProviderEmergencyStop> active = stops.findActive(now);
        Instant newExpiry = now.plus(properties.getStopDuration());
        ProviderEmergencyStop local = active.stream()
                .filter(s -> s.getScope() == Scope.CHANNEL && providerName.equals(s.getProvider())
                        && sameChannel(s.getChannel(), channel))
                .findFirst().orElse(null);
        if (local == null) {
            local = newStop(providerName, Scope.CHANNEL, channel, Origin.CHANNEL_REPORT, now, newExpiry);
        } else {
            local.setExpiresAt(newExpiry);
        }
        stops.save(local);

        Instant since = now.minus(properties.getWindow());
        Instant lastLift = stops.findLastGlobalLiftedAt(providerName).orElse(null);
        if (lastLift != null && lastLift.isAfter(since)) {
            since = lastLift;
        }
        List<EmergencyReport> counting = reports.findCounting(provider, since);
        int votes = VoteMatching.maxMatching(counting);
        boolean quorum = votes >= threshold;

        // An owner stop (no expiry) comes first: it is reported as such and never extended.
        ProviderEmergencyStop global = active.stream()
                .filter(s -> s.getScope() == Scope.GLOBAL && providerName.equals(s.getProvider()))
                .min(Comparator.comparing(s -> s.getExpiresAt() != null))
                .orElse(null);
        if (global != null) {
            if (global.getExpiresAt() != null && quorum) {
                global.setExpiresAt(newExpiry);
                stops.save(global);
                announce("extended", global);
                return new ReportOutcome(ReportOutcome.Kind.GLOBAL_EXTENDED, votes, threshold, newExpiry);
            }
            return new ReportOutcome(ReportOutcome.Kind.GLOBAL_ACTIVE, votes, threshold, global.getExpiresAt());
        }
        if (quorum) {
            ProviderEmergencyStop created = newStop(providerName, Scope.GLOBAL, null, Origin.THRESHOLD,
                    now, newExpiry);
            stops.save(created);
            announce("created", created);
            return new ReportOutcome(ReportOutcome.Kind.GLOBAL_STOP, votes, threshold, newExpiry);
        }
        return new ReportOutcome(ReportOutcome.Kind.CHANNEL_STOP, votes, threshold, local.getExpiresAt());
    }

    /** Stops a provider (or {@code all}) instance-wide until the owner resumes it. */
    public ProviderEmergencyStop ownerStop(String providerOrAll, String ownerLogin) {
        String name = normalize(providerOrAll);
        ProviderEmergencyStop stop = tx.execute(status -> stops.save(
                newStop(name, Scope.GLOBAL, null, Origin.OWNER, clock.instant(), null)));
        invalidate();
        log.warn("Emergency stop of {} by owner {}", name, ownerLogin);
        announce("created", stop);
        return stop;
    }

    /**
     * Lifts the global and local stops of a provider (an {@code ALL} stop is left alone), or every
     * active stop for {@code all}. Returns how many were lifted.
     */
    public int ownerResume(String providerOrAll, String ownerLogin) {
        String name = normalize(providerOrAll);
        Instant now = clock.instant();
        Integer lifted = tx.execute(status -> {
            List<ProviderEmergencyStop> toLift = stops.findActive(now).stream()
                    .filter(s -> ALL.equals(name) || name.equals(s.getProvider())).toList();
            toLift.forEach(s -> {
                s.setLiftedAt(now);
                s.setLiftedBy(ownerLogin);
            });
            stops.saveAll(toLift);
            toLift.stream().filter(s -> s.getScope() == Scope.GLOBAL).forEach(s -> announce("lifted", s));
            return toLift.size();
        });
        invalidate();
        return lifted == null ? 0 : lifted;
    }

    /** Lifts the channel's own stops and withdraws its standing reports; never touches a global stop. */
    public boolean liftChannel(UserAccount channel, String byLogin) {
        Instant now = clock.instant();
        Boolean changed = tx.execute(status -> {
            List<ProviderEmergencyStop> own = stops.findActive(now).stream()
                    .filter(s -> s.getScope() == Scope.CHANNEL && sameChannel(s.getChannel(), channel)).toList();
            own.forEach(s -> {
                s.setLiftedAt(now);
                s.setLiftedBy(byLogin);
            });
            stops.saveAll(own);
            List<EmergencyReport> standing = reports.findByChannelAndWithdrawnAtIsNull(channel);
            standing.forEach(r -> r.setWithdrawnAt(now));
            reports.saveAll(standing);
            return !own.isEmpty() || !standing.isEmpty();
        });
        invalidate();
        return Boolean.TRUE.equals(changed);
    }

    public List<ProviderEmergencyStop> activeStops() {
        return List.copyOf(cachedActive());
    }

    /** The global stops, and the ones local to the channel. */
    public List<ProviderEmergencyStop> activeStopsFor(UserAccount channel) {
        return cachedActive().stream()
                .filter(s -> s.getScope() == Scope.GLOBAL || sameChannel(s.getChannel(), channel)).toList();
    }

    private static boolean covers(ProviderEmergencyStop stop, UserAccount channel, GetterConfig.Provider provider) {
        boolean providerMatch = ALL.equals(stop.getProvider()) || provider.name().equals(stop.getProvider());
        return providerMatch && (stop.getScope() == Scope.GLOBAL || sameChannel(stop.getChannel(), channel));
    }

    private static boolean sameChannel(UserAccount a, UserAccount b) {
        return a != null && b != null && Objects.equals(a.getId(), b.getId());
    }

    private static ProviderEmergencyStop newStop(String provider, Scope scope, UserAccount channel, Origin origin,
                                                 Instant now, Instant expiresAt) {
        ProviderEmergencyStop stop = new ProviderEmergencyStop();
        stop.setProvider(provider);
        stop.setScope(scope);
        stop.setChannel(channel);
        stop.setOrigin(origin);
        stop.setCreatedAt(now);
        stop.setExpiresAt(expiresAt);
        return stop;
    }

    private static String normalize(String providerOrAll) {
        String name = providerOrAll.trim().toUpperCase();
        if (!ALL.equals(name)) {
            GetterConfig.Provider.valueOf(name);
        }
        return name;
    }

    private void announce(String action, ProviderEmergencyStop stop) {
        log.warn("Emergency stop {}: provider={} origin={} expiresAt={}",
                action, stop.getProvider(), stop.getOrigin(), stop.getExpiresAt());
        // "created" is counted as "stopped": the tag tells a new stop from an extension or a lifting
        meters.counter("catapult.emergency.stops", "provider", stop.getProvider(),
                "origin", stop.getOrigin().name(), "action", "created".equals(action) ? "stopped" : action)
                .increment();
        Map<String, Object> view = new HashMap<>();
        view.put("action", action);
        view.put("provider", stop.getProvider());
        view.put("origin", stop.getOrigin().name());
        view.put("expiresAt", stop.getExpiresAt());
        view.put("liftedAt", stop.getLiftedAt());
        adminEvents.emergencyStopChanged(view);
    }

    private List<ProviderEmergencyStop> cachedActive() {
        Instant now = clock.instant();
        List<ProviderEmergencyStop> snapshot = cached;
        Instant at = cachedAt;
        if (snapshot != null && at != null && Duration.between(at, now).compareTo(CACHE_TTL) < 0) {
            return snapshot;
        }
        List<ProviderEmergencyStop> fresh = List.copyOf(stops.findActive(now));
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    private void invalidate() {
        cached = null;
        cachedAt = null;
    }
}
