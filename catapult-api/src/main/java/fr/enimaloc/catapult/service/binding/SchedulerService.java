package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.service.debug.DebugContext;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DetectionTrace;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.DetectionTimeoutException;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.getter.ProviderStoppedException;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.getter.steam.SteamGameGetter;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.simulation.SimulationGate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class SchedulerService implements SchedulingConfigurer {

    private final UserAccountRepository userAccountRepository;
    private final GameGetterChain gameGetterChain;
    private final GameStateService gameStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Optional<SteamGameGetter> steamGameGetter;
    private final Optional<MinecraftPresenceGetter> minecraftPresenceGetter;
    private final BindingService bindingService;
    private final ObjectProvider<SimulationGate> simulationGate;
    private final SiteRole siteRole;
    private final PollCadence pollCadence;
    private final ActivityLogService activityLogService;
    /** Null in tests that build the service by hand: no detection is traced then. */
    private final DebugMemberRegistry debugMembers;
    private final DebugExchangeRecorder debugRecorder;

    /** The stopped provider each chain was last told about, so the log gets one line per stop and chain. */
    private final Map<UUID, GetterConfig.Provider> frozenChains = new ConcurrentHashMap<>();
    /**
     * The users whose last cycle found no game while one was known: a game ends only on a second cycle in a row
     * without it, since a platform sometimes answers a lone empty presence mid-game (Xbox's bare "Offline"). A manual
     * check ends it at once.
     */
    private final Set<UUID> unconfirmedEnds = ConcurrentHashMap.newKeySet();

    /**
     * Guards the prefetch→process→clear cycle so a manual {@link #triggerManualCheck}
     * can never interleave with the scheduled {@link #poll}: both getters cache one
     * cycle's results in a shared field, so running two cycles concurrently could let
     * one overwrite or clear the other's cache mid-read.
     */
    private final ReentrantLock cycleLock = new ReentrantLock();

    private static final long PREFETCH_TIMEOUT_SECONDS = 5;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::poll, pollTrigger());
    }

    /** Runs the poll every {@link PollCadence#tickSeconds()}, re-read after each run so an override applies live. */
    Trigger pollTrigger() {
        return context -> {
            Instant last = context.lastActualExecution();
            return last == null ? context.getClock().instant() : last.plusSeconds(pollCadence.tickSeconds());
        };
    }

    /** One cycle of the loop: polls the active accounts whose tier interval has elapsed. */
    public void poll() {
        if (!siteRole.isActive()) {
            return;
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        cycleLock.lock();
        try {
            // Without the simulation, the fictitious accounts of the mock profile are left alone: no platform knows them
            Instant now = Instant.now();
            List<UserAccount> dueUsers = userAccountRepository
                .findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE).stream()
                .filter(user -> !SimulationGate.ignoresFictitious(simulationGate, user))
                .filter(user -> pollCadence.isDue(user, now))
                .toList();

            prefetch(dueUsers);

            for (UserAccount user : dueUsers) {
                pollCadence.markPolled(user.getId(), now);
                try {
                    processUser(user, false);
                } catch (Exception e) {
                    log.error("Unexpected error during polling for user {}", user.getId(), e);
                }
                meterRegistry.counter("catapult.scheduler.users.polled").increment();
            }
        } finally {
            clearPrefetchCaches();
            cycleLock.unlock();
            sample.stop(Timer.builder("catapult.scheduler.poll.duration").register(meterRegistry));
        }
    }

    /**
     * Runs a single-user detection cycle outside the scheduled poll, for a user whose
     * bot is disabled (and so excluded from {@link #poll}'s query) but who still wants
     * a one-off game check — e.g. via the "recheck" button on their channel page.
     */
    public void triggerManualCheck(UserAccount user) {
        cycleLock.lock();
        try {
            prefetch(List.of(user));
            processUser(user, true);
            meterRegistry.counter("catapult.scheduler.manual-checks").increment();
        } finally {
            clearPrefetchCaches();
            cycleLock.unlock();
        }
    }

    private void prefetch(List<UserAccount> users) {
        steamGameGetter.ifPresent(getter -> awaitPrefetch("Steam",
            getter.prefetchBatch(users.stream().filter(u -> u.getSteamId() != null).toList())));
        minecraftPresenceGetter.ifPresent(getter -> awaitPrefetch("Minecraft", getter.prefetchBatch()));
    }

    /** Waits up to 5s for a getter's batch prefetch; on timeout or failure the cycle goes on with what it has. */
    private static void awaitPrefetch(String source, CompletableFuture<Void> prefetch) {
        try {
            prefetch.get(PREFETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            prefetch.cancel(false);
            log.warn("{} prefetch timed out after {}s — proceeding with partial results", source, PREFETCH_TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("{} prefetch failed: {}", source, e.getCause().getMessage());
        }
    }

    private void clearPrefetchCaches() {
        steamGameGetter.ifPresent(SteamGameGetter::clearCycleCache);
        minecraftPresenceGetter.ifPresent(MinecraftPresenceGetter::clearCycleCache);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            bindingService.refreshIncompleteBindings();
        } catch (Exception e) {
            log.error("Failed to refresh incomplete bindings at startup", e);
        }
    }

    @Scheduled(
        initialDelayString = "${app.retry.incomplete-interval-ms:21600000}",
        fixedRateString = "${app.retry.incomplete-interval-ms:21600000}")
    public void retryIncompleteBindings() {
        try {
            bindingService.refreshIncompleteBindings();
        } catch (Exception e) {
            log.error("Failed to refresh incomplete bindings", e);
        }
    }

    /**
     * Runs as the member (prefetches stay outside: they are batched across members). A member in debug mode gets
     * the cycle explained in a {@link DetectionTrace}, recorded once the cycle and its synchronous listeners are done.
     */
    /** {@code manual}: a check someone asked for, whose empty answer ends the game at once. */
    private void processUser(UserAccount user, boolean manual) {
        DebugContext.runAs(user.getId(), () -> {
            try (var trace = DetectionTrace.openIfDebugged(debugMembers, debugRecorder, user.getId())) {
                detect(user, manual);
            }
        });
    }

    private void detect(UserAccount user, boolean manual) {
        Optional<DetectedGame> detected;
        try {
            detected = gameGetterChain.resolve(user);
        } catch (DetectionTimeoutException e) {
            // Nothing learnt this cycle: the user keeps their state until a provider answers
            if (e instanceof ProviderStoppedException stopped) {
                // A freeze, not a failure: the activity log records it once, each cycle only counts it
                if (frozenChains.put(user.getId(), stopped.getProvider()) != stopped.getProvider()) {
                    activityLogService.addEntry(user.getId(), "WARN", stopped.getProvider().name()
                        + " is suspended by an emergency stop: detection is frozen until it is lifted");
                }
                if (DetectionTrace.active()) {
                    DetectionTrace.outcome("frozen", DetectionTrace.details("provider", stopped.getProvider()));
                }
                log.debug("Detection frozen for user {}: {}", user.getId(), e.getMessage());
                meterRegistry.counter("catapult.scheduler.detection.frozen").increment();
                return;
            }
            if (DetectionTrace.active()) {
                DetectionTrace.outcome("timeout", DetectionTrace.details("message", e.getMessage()));
            }
            log.warn("Detection skipped for user {}: {}", user.getId(), e.getMessage());
            meterRegistry.counter("catapult.scheduler.detection.timeouts").increment();
            return;
        }
        frozenChains.remove(user.getId());
        boolean firstSinceStart = gameStateService.markObserved(user);

        if (detected.isPresent()) {
            unconfirmedEnds.remove(user.getId());
            DetectedGame game = detected.get();
            if (gameStateService.hasChanged(user, game)) {
                if (DetectionTrace.active()) {
                    DetectionTrace.outcome("changed", DetectionTrace.details(
                        "from", tracedPreviousGame(user), "to", traced(game), "firstSinceStart", firstSinceStart));
                }
                gameStateService.updateState(user, game);
                log.debug("Game changed for user {}: {}", user.getId(), game.getSourceName());
                eventPublisher.publishEvent(new GameDetectedEvent(this, user, game, firstSinceStart));
            } else if (DetectionTrace.active()) {
                DetectionTrace.outcome("unchanged", DetectionTrace.details("game", traced(game)));
            }
        } else {
            Optional<DetectedGame> last = gameStateService.getLastKnownGame(user);
            if (last.isPresent() && !manual && unconfirmedEnds.add(user.getId())) {
                if (DetectionTrace.active()) {
                    DetectionTrace.outcome("unconfirmed", DetectionTrace.details("game", traced(last.get())));
                }
                log.debug("No game detected for user {} (was playing): waiting for the next cycle", user.getId());
            } else if (last.isPresent()) {
                unconfirmedEnds.remove(user.getId());
                if (DetectionTrace.active()) {
                    DetectionTrace.outcome("cleared", DetectionTrace.details("from", traced(last.get())));
                }
                gameStateService.clearState(user);
                log.debug("No game detected for user {} (was playing)", user.getId());
                eventPublisher.publishEvent(new NoGameDetectedEvent(this, user));
            } else {
                unconfirmedEnds.remove(user.getId());
                DetectionTrace.outcome("nothing", null);
            }
        }
    }

    /** The game the user was last known to play, as the trace shows it; null when unknown or unreadable. */
    private Map<String, Object> tracedPreviousGame(UserAccount user) {
        try {
            return gameStateService.getLastKnownGame(user).map(SchedulerService::traced).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read the previous game of {} for the detection trace: {}", user.getId(), e.toString());
            return null;
        }
    }

    /** A game as the detection trace shows it. */
    private static Map<String, Object> traced(DetectedGame game) {
        return DetectionTrace.details("sourceId", game.getSourceId(), "sourceType", game.getSourceType(),
            "sourceName", game.getSourceName());
    }
}
