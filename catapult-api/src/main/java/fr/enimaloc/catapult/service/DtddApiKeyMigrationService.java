package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.DtddApiKeyEntry;
import fr.enimaloc.catapult.getter.DtddApiKeyRotator;
import fr.enimaloc.catapult.repository.DtddApiKeyRepository;
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
@ConditionalOnBooleanProperty("dtdd.enabled")
@Order(2)
public class DtddApiKeyMigrationService implements ApplicationRunner {

    private final DtddApiKeyRepository repository;

    @Setter
    private DtddApiKeyRotator rotator;

    public DtddApiKeyMigrationService(DtddApiKeyRepository repository,
                                      @Autowired(required = false) DtddApiKeyRotator rotator) {
        this.repository = repository;
        this.rotator = rotator;
    }

    @Setter
    @Value("${dtdd.api-keys:}")
    private List<String> apiKeys = new ArrayList<>();

    @Setter
    @Value("${dtdd.api-key:}")
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
                repository.save(new DtddApiKeyEntry(key));
                imported++;
            }
        }

        if (imported > 0) {
            log.info("Imported {} DoesTheDogDie API key(s) from config into DB", imported);
            if (rotator != null) rotator.refreshKeys();
        }
    }
}
