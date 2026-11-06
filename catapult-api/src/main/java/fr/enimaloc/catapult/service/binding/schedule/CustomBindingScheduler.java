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

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final Clock clock;

    @Autowired
    public CustomBindingScheduler(CustomBindingScheduleRepository schedules,
                                  CustomBindingScheduleRefusalRepository refusals,
                                  CustomBindingActivationRepository activationRepository,
                                  CustomBindingActivations activations, CustomBindingService customBindingService,
                                  StreamStateService streamStateService, PremiumService premiumService,
                                  UserAccountRepository users, SiteRole siteRole) {
        this(schedules, refusals, activationRepository, activations, customBindingService, streamStateService,
                premiumService, users, siteRole, Clock.systemUTC());
    }

    CustomBindingScheduler(CustomBindingScheduleRepository schedules,
                           CustomBindingScheduleRefusalRepository refusals,
                           CustomBindingActivationRepository activationRepository,
                           CustomBindingActivations activations, CustomBindingService customBindingService,
                           StreamStateService streamStateService, PremiumService premiumService,
                           UserAccountRepository users, SiteRole siteRole, Clock clock) {
        this.schedules = schedules;
        this.refusals = refusals;
        this.activationRepository = activationRepository;
        this.activations = activations;
        this.customBindingService = customBindingService;
        this.streamStateService = streamStateService;
        this.premiumService = premiumService;
        this.users = users;
        this.siteRole = siteRole;
        this.clock = clock;
    }

    private record Due(CustomBindingSchedule schedule, ScheduleOccurrences.Occurrence occurrence) {}

    /** Evaluates every streamer with a slot to look at or a scheduled activation to end. */
    @Scheduled(fixedDelayString = "${catapult.custom-binding-schedule.tick-ms:60000}")
    void tick() {
        if (!siteRole.isActive()) {
            return;
        }
        Instant now = clock.instant();
        refusals.deleteByOccurrenceEndBefore(now);
        Set<UUID> userIds = new LinkedHashSet<>(schedules.findUserIdsWithUnpausedSchedules());
        userIds.addAll(activationRepository.findUserIdsByOrigin(Origin.SCHEDULED));
        for (UUID userId : userIds) {
            try {
                users.findById(userId).ifPresent(user -> DebugContext.runAs(userId, () -> evaluate(user, now)));
            } catch (Exception e) {
                log.error("Custom binding schedule evaluation failed for user {}", userId, e);
            }
        }
    }

    /**
     * Ordered after the game detection's own stream-start handling, which would otherwise replace the binding this
     * puts in place.
     */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onStreamOnline(StreamOnlineEvent event) {
        evaluate(event.getUser(), clock.instant());
    }

    /** After commit: the change is published from inside transactions and must see what they wrote. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSchedulesChanged(CustomBindingSchedulesChangedEvent event) {
        users.findById(event.userId()).ifPresent(user -> evaluate(user, clock.instant()));
    }

    void evaluate(UserAccount user, Instant now) {
        if (!customBindingService.enabled(user) || !streamStateService.isLive(user)) {
            return;
        }
        Optional<Due> due = schedules.findByCustomBindingUserId(user.getId()).stream()
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
            if (active.isPresent() && (alreadyServes(active.get(), d) || manualChoiceDuring(active.get(), d))) {
                return;
            }
            // A failed activation is retried by the next tick
            customBindingService.activateScheduled(user, d.schedule().getCustomBinding(), d.schedule().getId(),
                    d.occurrence());
        } else if (active.filter(a -> a.getOrigin() == Origin.SCHEDULED).isPresent()) {
            customBindingService.endScheduled(user);
        }
    }

    /** A manual choice made during the occurrence stands: the schedule does not take it back. */
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
