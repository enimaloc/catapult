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
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

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
    /** One lock per streamer, so the tick and the events never evaluate the same one at once. */
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public CustomBindingScheduler(CustomBindingScheduleRepository schedules,
                                  CustomBindingScheduleRefusalRepository refusals,
                                  CustomBindingActivationRepository activationRepository,
                                  CustomBindingActivations activations, CustomBindingService customBindingService,
                                  StreamStateService streamStateService, PremiumService premiumService,
                                  UserAccountRepository users, SiteRole siteRole) {
        this(schedules, refusals, activationRepository, activations, customBindingService, streamStateService,
                premiumService, users, siteRole, newExecutor(), Clock.systemUTC());
    }

    CustomBindingScheduler(CustomBindingScheduleRepository schedules,
                           CustomBindingScheduleRefusalRepository refusals,
                           CustomBindingActivationRepository activationRepository,
                           CustomBindingActivations activations, CustomBindingService customBindingService,
                           StreamStateService streamStateService, PremiumService premiumService,
                           UserAccountRepository users, SiteRole siteRole, Executor executor,
                           Clock clock) {
        this.schedules = schedules;
        this.refusals = refusals;
        this.activationRepository = activationRepository;
        this.activations = activations;
        this.customBindingService = customBindingService;
        this.streamStateService = streamStateService;
        this.premiumService = premiumService;
        this.users = users;
        this.siteRole = siteRole;
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

    /** Evaluates every streamer with a slot to look at or a scheduled activation to end. */
    @Scheduled(fixedDelayString = "${catapult.custom-binding-schedule.tick-ms:60000}")
    void tick() {
        if (!siteRole.isActive()) {
            return;
        }
        Instant now = clock.instant();
        try {
            refusals.deleteByOccurrenceEndBefore(now);
        } catch (Exception e) {
            log.error("Custom binding schedule refusal purge failed", e);
        }
        Set<UUID> userIds = new LinkedHashSet<>(schedules.findUserIdsWithUnpausedSchedules());
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
     * it refused it already, so this one was made before the occurrence or offline.
     */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onStreamOnline(StreamOnlineEvent event) {
        UserAccount user = event.getUser();
        DebugContext.runAs(user.getId(), () -> evaluate(user, clock.instant(), true));
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
        ReentrantLock lock = locks.computeIfAbsent(user.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            doEvaluate(user, now, goingLive);
        } finally {
            lock.unlock();
        }
    }

    private void doEvaluate(UserAccount user, Instant now, boolean goingLive) {
        if (!customBindingService.enabled(user) || !streamStateService.isLive(user)) {
            return;
        }
        Optional<Due> due = schedules.findWithBindingByCustomBindingUserId(user.getId()).stream()
                .filter(s -> !s.isPaused()
                        && premiumService.canSchedule(user, s.getCustomBinding().getTwitchGameId(), s.getRepeat()))
                .map(s -> ScheduleOccurrences.current(s, now, PremiumService.ZONE)
                        .map(occurrence -> new Due(s, occurrence)))
                .flatMap(Optional::stream)
                .filter(d -> !refusals.existsByScheduleIdAndOccurrenceStart(d.schedule().getId(),
                        d.occurrence().start()))
                // The occurrence that started last wins
                .max(Comparator.comparing((Due d) -> d.occurrence().start())
                        .thenComparing(d -> d.schedule().getId()));
        Optional<CustomBindingActivation> active = activations.current(user);
        if (due.isPresent()) {
            Due d = due.get();
            if (active.isPresent() && (alreadyServes(active.get(), d)
                    || !goingLive && manualChoiceDuring(active.get(), d))) {
                return;
            }
            // A failed activation is retried by the next tick
            customBindingService.activateScheduled(user, d.schedule().getCustomBinding(), d.schedule().getId(),
                    d.occurrence());
        } else if (active.filter(a -> a.getOrigin() == Origin.SCHEDULED).isPresent()) {
            customBindingService.endScheduled(user);
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
