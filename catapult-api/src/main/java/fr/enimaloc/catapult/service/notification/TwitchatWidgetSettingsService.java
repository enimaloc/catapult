package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.channel.TwitchatData;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
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

    /**
     * Sets the Twitchat API the user's pages speak, pushed live like the OBS settings.
     *
     * @throws IllegalArgumentException when not one of {@link TwitchatData}'s
     */
    @Transactional
    public TwitchatWidgetSettings updateBranch(UserAccount user, String branch) {
        if (!TwitchatData.isValid(branch)) {
            throw new IllegalArgumentException("Unknown Twitchat branch " + branch);
        }
        TwitchatWidgetSettings settings = getOrCreate(user);
        settings.setTwitchatBranch(branch);
        TwitchatWidgetSettings saved = repository.save(settings);
        publishWidgetConfig(saved);
        return saved;
    }

    /**
     * The OBS connection of the user's enabled relay, password decrypted: what the logged-in
     * user's own browser connects to OBS with. Empty when disabled or never configured.
     */
    @Transactional(readOnly = true)
    public Optional<TwitchatWidgetConfig> enabledConfig(UUID userId) {
        return repository.findById(userId).filter(TwitchatWidgetSettings::isEnabled).map(this::config);
    }

    private TwitchatWidgetConfig config(TwitchatWidgetSettings settings) {
        String password = settings.getObsPasswordEncrypted() == null
                ? null : tokenEncryptionService.decrypt(settings.getObsPasswordEncrypted());
        return new TwitchatWidgetConfig(settings.getObsHost(), settings.getObsPort(), password,
                settings.getTwitchatBranch());
    }

    // Pushed live so a widget page already open in a browser tab or OBS browser source picks
    // up host/port/password changes and reconnects without needing a manual reload. Not sent
    // from regenerateToken(): the whole point of regenerating is to cut off any existing
    // session using the old token, so it must NOT be kept alive with fresh values.
    private void publishWidgetConfig(TwitchatWidgetSettings settings) {
        channelEventPublisher.twitchatWidgetSettingsUpdated(settings.getUser().getId(), config(settings));
    }

    @Transactional
    public TwitchatWidgetSettings regenerateToken(UserAccount user) {
        TwitchatWidgetSettings settings = getOrCreate(user);
        widgetTokenService.regenerate(user);
        return settings;
    }
}
