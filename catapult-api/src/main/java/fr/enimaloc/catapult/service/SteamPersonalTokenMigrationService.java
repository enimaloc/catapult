package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Profile("!mock")
@ConditionalOnBooleanProperty("steam.enabled")
@Order(3)
@RequiredArgsConstructor
public class SteamPersonalTokenMigrationService implements ApplicationRunner {

    private final UserAccountRepository userAccountRepository;
    private final SteamApiKeyRepository steamApiKeyRepository;
    private final TokenEncryptionService tokenEncryptionService;

    @Autowired(required = false)
    private SteamApiKeyRotator rotator;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var users = userAccountRepository.findAll();
        int migrated = 0;
        for (var user : users) {
            if (user.getSteamPersonalToken() == null) continue;
            if (steamApiKeyRepository.findByOwner(user).isPresent()) continue;
            try {
                String plainToken = tokenEncryptionService.decrypt(user.getSteamPersonalToken());
                if (steamApiKeyRepository.existsById(plainToken)) continue;
                SteamApiKeyEntry entry = new SteamApiKeyEntry(plainToken);
                entry.setOwner(user);
                entry.setExclusive(!user.isSteamTokenShared());
                steamApiKeyRepository.save(entry);
                migrated++;
            } catch (Exception e) {
                log.warn("Could not migrate Steam personal token for user {}", user.getId(), e);
            }
        }
        if (migrated > 0) {
            log.info("Migrated {} user Steam personal token(s) into steam_api_key", migrated);
            if (rotator != null) rotator.refreshKeys();
        }
    }
}
