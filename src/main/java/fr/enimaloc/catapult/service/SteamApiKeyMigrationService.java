package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Profile("!mock")
@ConditionalOnBooleanProperty("steam.enabled")
@Order(2)
public class SteamApiKeyMigrationService implements ApplicationRunner {

    private final SteamApiKeyRepository repository;

    @Setter
    private SteamApiKeyRotator rotator;

    public SteamApiKeyMigrationService(SteamApiKeyRepository repository,
                                       @Autowired(required = false) SteamApiKeyRotator rotator) {
        this.repository = repository;
        this.rotator = rotator;
    }

    @Setter
    @Value("${steam.api-keys:}")
    private List<String> apiKeys = new ArrayList<>();

    @Setter
    @Value("${steam.api-key:}")
    private String legacyApiKey = "";

    @Override
    public void run(ApplicationArguments args) {
        List<String> allKeys = new ArrayList<>(apiKeys.stream().distinct().toList());
        if (!legacyApiKey.isBlank() && !allKeys.contains(legacyApiKey)) {
            allKeys.add(legacyApiKey);
        }

        int imported = 0;
        for (String key : allKeys) {
            if (key.isBlank()) continue;
            if (!repository.existsById(key)) {
                repository.save(new SteamApiKeyEntry(key));
                imported++;
            }
        }

        if (imported > 0) {
            log.info("Imported {} Steam API key(s) from config into DB", imported);
            if (rotator != null) rotator.refreshKeys();
        }
    }
}
