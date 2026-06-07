package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@DependsOn("flyway")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock.steam", havingValue = "false", matchIfMissing = true)
public class SteamLibraryCacheService {

    private final SteamApiClient steamApiClient;
    private final IgdbService igdbService;
    private final UserAccountRepository userAccountRepository;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void preloadAllUserLibraries() {
        List<UserAccount> users = userAccountRepository.findBySteamIdNotNull();
        if (users.isEmpty()) {
            log.info("No users with Steam linked — skipping library preload");
            igdbService.prewarmCclCache();
            return;
        }

        log.info("Pre-caching Steam libraries for {} user(s) at startup", users.size());
        List<CompletableFuture<Void>> futures = users.stream()
            .map(this::cacheLibraryAsync)
            .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        igdbService.prewarmCclCache();
    }

    @Async
    @EventListener
    public void onSteamLinked(SteamLinkedEvent event) {
        cacheLibraryAsync(event.getUser()).join();
        igdbService.prewarmCclCache();
    }

    private CompletableFuture<Void> cacheLibraryAsync(UserAccount user) {
        if (user.getSteamId() == null) return CompletableFuture.completedFuture(null);

        log.info("Pre-caching Steam library for user {} (steamId={})", user.getId(), user.getSteamId());
        return steamApiClient.getOwnedGameIds(user.getSteamId())
            .thenAccept(appIds -> {
                if (appIds.isEmpty()) {
                    log.warn("No owned games returned for steamId={} (private profile?)", user.getSteamId());
                    return;
                }
                igdbService.prewarmSteamAppIds(appIds);
            })
            .exceptionally(e -> {
                log.warn("Failed to cache Steam library for steamId={}: {}", user.getSteamId(), e.getMessage());
                return null;
            });
    }
}
