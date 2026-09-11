package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.WidgetTokenService;
import fr.enimaloc.catapult.service.notification.dto.TwitchatWidgetConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ChannelEventPublisher channelEventPublisher;
    private final WidgetTokenService widgetTokenService;

    @Transactional
    public TwitchatWidgetSettings getOrCreate(UserAccount user) {
        widgetTokenService.getOrCreate(user);
        return repository.findById(user.getId()).orElseGet(() -> {
            TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
            settings.setUser(user);
            settings.setEnabled(false);
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
        TwitchatWidgetSettings saved = repository.save(settings);
        publishWidgetConfig(saved);
        return saved;
    }

    // Pushed live so a widget page already open in a browser tab or OBS browser source picks
    // up host/port/password changes and reconnects without needing a manual reload. Not sent
    // from regenerateToken(): the whole point of regenerating is to cut off any existing
    // session using the old token, so it must NOT be kept alive with fresh values.
    private void publishWidgetConfig(TwitchatWidgetSettings settings) {
        String password = settings.getObsPasswordEncrypted() == null
                ? null : tokenEncryptionService.decrypt(settings.getObsPasswordEncrypted());
        channelEventPublisher.twitchatWidgetSettingsUpdated(settings.getUser().getId(),
                new TwitchatWidgetConfig(settings.getObsHost(), settings.getObsPort(), password));
    }

    @Transactional
    public TwitchatWidgetSettings regenerateToken(UserAccount user) {
        TwitchatWidgetSettings settings = getOrCreate(user);
        widgetTokenService.regenerate(user);
        return settings;
    }
}
