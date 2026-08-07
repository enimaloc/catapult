package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.GameDetectedEvent;
import fr.enimaloc.catapult.event.NoGameDetectedEvent;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.GameGetterChain;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.getter.SteamGameGetter;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
public class SchedulerService {

    private final UserAccountRepository userAccountRepository;
    private final GameGetterChain gameGetterChain;
    private final GameStateService gameStateService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Optional<SteamGameGetter> steamGameGetter;
    private final Optional<MinecraftPresenceGetter> minecraftPresenceGetter;
    private final BindingService bindingService;

    /**
     * Guards the prefetch→process→clear cycle so a manual {@link #triggerManualCheck}
     * can never interleave with the scheduled {@link #poll}: both getters cache one
     * cycle's results in a shared field, so running two cycles concurrently could let
     * one overwrite or clear the other's cache mid-read.
     */
    private final ReentrantLock cycleLock = new ReentrantLock();

    @Scheduled(fixedRateString = "${app.polling.interval-seconds:60}000")
    public void poll() {
        Timer.Sample sample = Timer.start(meterRegistry);
        cycleLock.lock();
        try {
            List<UserAccount> activeUsers = userAccountRepository
                .findByBotEnabledTrueAndStatus(UserAccount.Status.ACTIVE);

            prefetch(activeUsers);

            for (UserAccount user : activeUsers) {
                try {
                    processUser(user);
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
            processUser(user);
            meterRegistry.counter("catapult.scheduler.manual-checks").increment();
        } finally {
            clearPrefetchCaches();
            cycleLock.unlock();
        }
    }

    private void prefetch(List<UserAccount> users) {
        steamGameGetter.ifPresent(getter -> {
            CompletableFuture<Void> prefetch = getter.prefetchBatch(
                users.stream().filter(u -> u.getSteamId() != null).toList()
            );
            try {
                prefetch.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                prefetch.cancel(false);
                log.warn("Steam prefetch timed out after 5s — proceeding with partial results");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("Steam prefetch failed: {}", e.getCause().getMessage());
            }
        });

        minecraftPresenceGetter.ifPresent(getter -> {
            CompletableFuture<Void> prefetch = getter.prefetchBatch();
            try {
                prefetch.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                prefetch.cancel(false);
                log.warn("Minecraft prefetch timed out after 5s — proceeding with partial results");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("Minecraft prefetch failed: {}", e.getCause().getMessage());
            }
        });
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

    private void processUser(UserAccount user) {
        Optional<DetectedGame> detected = gameGetterChain.resolve(user);

        if (detected.isPresent()) {
            DetectedGame game = detected.get();
            if (gameStateService.hasChanged(user, game)) {
                gameStateService.updateState(user, game);
                log.debug("Game changed for user {}: {}", user.getId(), game.getSourceName());
                eventPublisher.publishEvent(new GameDetectedEvent(this, user, game));
            }
        } else {
            if (gameStateService.getLastKnownGame(user).isPresent()) {
                gameStateService.clearState(user);
                log.debug("No game detected for user {} (was playing)", user.getId());
                eventPublisher.publishEvent(new NoGameDetectedEvent(this, user));
            }
        }
    }
}
