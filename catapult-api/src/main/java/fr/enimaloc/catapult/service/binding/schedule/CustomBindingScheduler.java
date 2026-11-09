package fr.enimaloc.catapult.service.binding.schedule;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation;
import fr.enimaloc.catapult.domain.binding.CustomBindingActivation.Origin;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.event.CustomBindingSchedulesChangedEvent;
import fr.enimaloc.catapult.event.StreamOnlineEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingActivationRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRefusalRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingService.ActivationResult;
import fr.enimaloc.catapult.service.binding.CustomBindingUserLocks;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.premium.PremiumService;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Switches custom bindings on and off along their schedule while the stream is live. Nothing depends on it: the
 * services it drives reach it through Spring events, which keeps their dependency graph acyclic.
 */
@Slf4j
@Service
public class CustomBindingScheduler {

    private final CustomBindingScheduleRepository schedules;
    private final CustomBindingScheduleRefusalRepository refusals;
    private final CustomBindingActivationRepository activationRepository;
    private final CustomBindingActivations activations;
    private final CustomBindingService customBindingService;
    private final StreamStateService streamStateService;
    private final PremiumService premiumService;
    private final UserAccountRepository users;
    private final SiteRole siteRole;
    private final Executor executor;
    private final Clock clock;
    /** One lock per streamer, shared with the manual choices, so nothing else decides for them mid-evaluation. */
    private final CustomBindingUserLocks locks;
    /**
     * Per streamer, when the manual activation the stream start set out to replace was made: picked offline during
     * the occurrence, it does not stand, and the ticks retry a replacement that failed.
     */
    private final Map<UUID, Instant> replacedManual = new ConcurrentHashMap<>();
    /** The occurrences whose activation failed, until they end: retried less and less often, then given up. */
    private final Map<OccurrenceKey, Failures> failures = new ConcurrentHashMap<>();

    /** Activation attempts for an occurrence, the first one included: then it is given up. */
    static final int MAX_ATTEMPTS = 5;

    private record OccurrenceKey(UUID scheduleId, Instant start) {}

    /** {@code count} attempts failed; the next one not before {@code nextTry}; forgotten once {@code end} is past. */
    private record Failures(int count, Instant nextTry, Instant end) {}

    @Autowired
    public CustomBindingScheduler(CustomBindingScheduleRepository schedules,
                                  CustomBindingScheduleRefusalRepository refusals,
                                  CustomBindingActivationRepository activationRepository,
                                  CustomBindingActivations activations, CustomBindingService customBindingService,
                                  StreamStateService streamStateService, PremiumService premiumService,
                                  UserAccountRepository users, SiteRole siteRole, CustomBindingUserLocks locks) {
        this(schedules, refusals, activationRepository, activations, customBindingService, streamStateService,
                premiumService, users, siteRole, locks, newExecutor(), Clock.systemUTC());
    }

    CustomBindingScheduler(CustomBindingScheduleRepository schedules,
                           CustomBindingScheduleRefusalRepository refusals,
                           CustomBindingActivationRepository activationRepository,
                           CustomBindingActivations activations, CustomBindingService customBindingService,
                           StreamStateService streamStateService, PremiumService premiumService,
                           UserAccountRepository users, SiteRole siteRole, CustomBindingUserLocks locks,
                           Executor executor, Clock clock) {
        this.schedules = schedules;
        this.refusals = refusals;
        this.activationRepository = activationRepository;
        this.activations = activations;
        this.customBindingService = customBindingService;
        this.streamStateService = streamStateService;
        this.premiumService = premiumService;
        this.users = users;
        this.siteRole = siteRole;
        this.locks = locks;
        this.executor = executor;
        this.clock = clock;
    }

    /** Private on purpose: a second TaskExecutor bean would make the unqualified {@code @Async} lookups ambiguous. */
    private static ThreadPoolTaskExecutor newExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(1000);
        exec.setThreadNamePrefix("custom-binding-schedule-");
        exec.setTaskDecorator(DebugContext::wrap);
        exec.initialize();
        return exec;
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            pool.shutdown();
        }
    }

    private record Due(CustomBindingSchedule schedule, ScheduleOccurrences.Occurrence occurrence) {}

    /**
     * Evaluates every streamer with a slot to look at or a scheduled activation to end. At second 0 of every minute,
     * when the slots (typed to the minute) start and end, not a minute after the API started.
     */
    @Scheduled(cron = "0 * * * * *")
    void tick() {
        if (!siteRole.isActive()) {
            return;
        }
        Instant now = clock.instant();
        failures.values().removeIf(failed -> !failed.end().isAfter(now));
        try {
            refusals.deleteByOccurrenceEndBefore(now);
        } catch (Exception e) {
            log.error("Custom binding schedule refusal purge failed", e);
        }
        // Wall times of any zone (UTC-12 to UTC+14), and a repeated slot running past midnight: two days back
        Set<UUID> userIds = new LinkedHashSet<>(schedules.findUserIdsWithSchedulesStillDue(
                LocalDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(2)));
        userIds.addAll(activationRepository.findUserIdsByOrigin(Origin.SCHEDULED));
        for (UUID userId : userIds) {
            try {
                users.findById(userId).ifPresent(user -> DebugContext.runAs(userId, () -> evaluate(user, now, false)));
            } catch (Exception e) {
                log.error("Custom binding schedule evaluation failed for user {}", userId, e);
            }
        }
    }

    /**
     * Ordered after the game detection's own stream-start handling, which would otherwise replace the binding this
     * puts in place. A manual activation does not hold back the running occurrence here: one made while live during
     * it refused it already, so this one was made before the occurrence or offline. A failure is logged, not
     * thrown: the stream start's other listeners still run, and the next tick tries again.
     */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        try {
            DebugContext.runAs(user.getId(), () -> evaluate(user, clock.instant(), true));
        } catch (Exception e) {
            log.error("Custom binding schedule evaluation failed at stream start for user {}", user.getId(), e);
        }
    }

    /**
     * After commit: the change is published from inside transactions and must see what they wrote. The evaluation is
     * handed to another thread: on this one the finished transaction's resources are still bound, and the writes of
     * an evaluation would join it and be lost.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSchedulesChanged(CustomBindingSchedulesChangedEvent event) {
        try {
            executor.execute(() -> users.findById(event.userId())
                    .ifPresent(user -> DebugContext.runAs(user.getId(), () -> evaluate(user, clock.instant(), false))));
        } catch (RejectedExecutionException e) {
            // The next tick catches up
            log.warn("Custom binding schedule evaluation for user {} rejected", event.userId(), e);
        }
    }

    void evaluate(UserAccount user, Instant now) {
        evaluate(user, now, false);
    }

    private void evaluate(UserAccount user, Instant now, boolean goingLive) {
        locks.run(user.getId(), () -> doEvaluate(user, now, goingLive));
    }

    private void doEvaluate(UserAccount user, Instant now, boolean goingLive) {
        if (!customBindingService.enabled(user) || !streamStateService.isLive(user)) {
            return;
        }
        Optional<Due> due = schedules.findWithBindingByCustomBindingUserId(user.getId()).stream()
                .filter(s -> !s.isPaused()
                        && premiumService.canSchedule(user, s.getCustomBinding().getTwitchGameId(), s.getRepeat()))
                .map(s -> ScheduleOccurrences.current(s, now, s.zone())
                        .map(occurrence -> new Due(s, occurrence)))
                .flatMap(Optional::stream)
                .filter(d -> !refusals.existsByScheduleIdAndOccurrenceStart(d.schedule().getId(),
                        d.occurrence().start()))
                // The occurrence that started last wins
                .max(Comparator.comparing((Due d) -> d.occurrence().start())
                        .thenComparing(d -> d.schedule().getId()));
        Optional<CustomBindingActivation> active = activations.current(user);
        if (due.isEmpty()) {
            replacedManual.remove(user.getId());
            if (active.filter(a -> a.getOrigin() == Origin.SCHEDULED).isPresent()) {
                customBindingService.endScheduled(user);
            }
            return;
        }
        Due d = due.get();
        if (active.isPresent() && alreadyServes(active.get(), d)) {
            replacedManual.remove(user.getId());
            return;
        }
        if (active.isPresent() && manualChoiceDuring(active.get(), d)) {
            Instant activatedAt = active.get().getActivatedAt();
            if (goingLive) {
                replacedManual.put(user.getId(), activatedAt);
            } else if (!activatedAt.equals(replacedManual.get(user.getId()))) {
                return;
            }
        }
        activate(user, d, now);
    }

    /** Activates {@code d}'s binding, unless its occurrence failed too often or too recently. */
    private void activate(UserAccount user, Due d, Instant now) {
        OccurrenceKey key = new OccurrenceKey(d.schedule().getId(), d.occurrence().start());
        Failures failed = failures.get(key);
        if (failed != null && (failed.count() >= MAX_ATTEMPTS || now.isBefore(failed.nextTry()))) {
            return;
        }
        ActivationResult result = null;
        try {
            result = customBindingService.activateScheduled(user, d.schedule().getCustomBinding(),
                    d.schedule().getId(), d.occurrence());
        } finally {
            if (result == ActivationResult.APPLIED) {
                failures.remove(key);
            } else {
                failed(user, key, failed, d, now);
            }
        }
    }

    /** One more failed attempt: the next one waits 1, 2, 4 then 8 minutes, after which the occurrence is given up. */
    private void failed(UserAccount user, OccurrenceKey key, Failures before, Due d, Instant now) {
        int count = before == null ? 1 : before.count() + 1;
        failures.put(key, new Failures(count, now.plus(Duration.ofMinutes(1L << (count - 1))), d.occurrence().end()));
        if (count == MAX_ATTEMPTS) {
            log.warn("Custom binding schedule {} given up for user {} until {}: {} activations failed",
                    d.schedule().getId(), user.getId(), d.occurrence().end(), count);
        }
    }

    /** A manual choice made live during the occurrence stands: the schedule does not take it back. */
    private static boolean manualChoiceDuring(CustomBindingActivation activation, Due due) {
        return activation.getOrigin() == Origin.MANUAL
                && !activation.getActivatedAt().isBefore(due.occurrence().start());
    }

    private static boolean alreadyServes(CustomBindingActivation activation, Due due) {
        return activation.getOrigin() == Origin.SCHEDULED
                && due.schedule().getId().equals(activation.getScheduleId())
                && due.occurrence().start().equals(activation.getOccurrenceStart());
    }
}
