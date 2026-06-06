package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.SteamLinkedEvent;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

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
    @PostConstruct
    public void preloadAllUserLibraries() {
        List<UserAccount> users = userAccountRepository.findBySteamIdNotNull();
        if (users.isEmpty()) {
            log.info("No users with Steam linked — skipping library preload");
        } else {
            log.info("Pre-caching Steam libraries for {} user(s) at startup", users.size());
            users.forEach(this::cacheLibrary);
        }
        igdbService.prewarmCclCache();
    }

    @Async
    @EventListener
    public void onSteamLinked(SteamLinkedEvent event) {
        cacheLibrary(event.getUser());
        igdbService.prewarmCclCache();
    }

    private void cacheLibrary(UserAccount user) {
        if (user.getSteamId() == null) return;

        log.info("Pre-caching Steam library for user {} (steamId={})", user.getId(), user.getSteamId());
        List<String> appIds = steamApiClient.getOwnedGameIds(user.getSteamId());
        if (appIds.isEmpty()) {
            log.warn("No owned games returned for steamId={} (private profile?)", user.getSteamId());
            return;
        }

        igdbService.prewarmSteamAppIds(appIds);
    }
}
