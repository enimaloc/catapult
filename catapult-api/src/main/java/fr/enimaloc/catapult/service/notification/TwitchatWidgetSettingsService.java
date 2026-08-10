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

    // Same values shown as placeholder text in the settings form — a blank field there looks
    // filled-in to the user, so a blank submission is treated as "use the placeholder" rather
    // than silently persisting null (which the relay page's obsHost/obsPort guard then hides).
    private static final String DEFAULT_OBS_HOST = "127.0.0.1";
    private static final int DEFAULT_OBS_PORT = 4455;

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
        settings.setObsHost(obsHost == null || obsHost.isBlank() ? DEFAULT_OBS_HOST : obsHost);
        settings.setObsPort(obsPort == null ? DEFAULT_OBS_PORT : obsPort);
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
