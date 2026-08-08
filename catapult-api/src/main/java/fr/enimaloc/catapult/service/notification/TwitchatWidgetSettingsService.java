package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TwitchatWidgetSettingsService {

    private final TwitchatWidgetSettingsRepository repository;
    private final TokenEncryptionService tokenEncryptionService;

    @Transactional
    public TwitchatWidgetSettings getOrCreate(UserAccount user) {
        return repository.findById(user.getId()).orElseGet(() -> {
            TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
            settings.setUser(user);
            settings.setEnabled(false);
            settings.setWidgetToken(UUID.randomUUID());
            return repository.save(settings);
        });
    }

    @Transactional
    public TwitchatWidgetSettings updateSettings(UserAccount user, boolean enabled, String obsHost,
                                                  Integer obsPort, String obsPasswordPlaintext) {
        TwitchatWidgetSettings settings = getOrCreate(user);
        settings.setEnabled(enabled);
        settings.setObsHost(obsHost);
        settings.setObsPort(obsPort);
        if (obsPasswordPlaintext != null) {
            settings.setObsPasswordEncrypted(tokenEncryptionService.encrypt(obsPasswordPlaintext));
        }
        return repository.save(settings);
    }

    @Transactional
    public TwitchatWidgetSettings regenerateToken(UserAccount user) {
        TwitchatWidgetSettings settings = getOrCreate(user);
        settings.setWidgetToken(UUID.randomUUID());
        return repository.save(settings);
    }
}
