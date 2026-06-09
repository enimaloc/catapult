package fr.enimaloc.catapult.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.mock.steam", havingValue = "true")
public class MockSteamLibraryCacheService {

    @PostConstruct
    public void preloadAllUserLibraries() {
        log.debug("[Mock Steam] preloadAllUserLibraries() — no-op");
    }
}
