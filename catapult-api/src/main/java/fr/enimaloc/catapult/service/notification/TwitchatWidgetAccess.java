package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.channel.ConfigResponse;
import fr.enimaloc.catapult.common.dto.notification.NotificationFlushResultDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * What the Twitchat OBS widget reads by its widget token: whether it is enabled, its OBS-websocket settings, and
 * its owner's pending Twitchat notifications. Each answer is empty for an unknown token, and the settings and
 * notifications also while the widget is disabled.
 */
@Service
@RequiredArgsConstructor
public class TwitchatWidgetAccess {

    private final TwitchatWidgetSettingsRepository widgetSettingsRepository;
    private final WidgetTokenService widgetTokenService;
    private final TokenEncryptionService tokenEncryptionService;
    private final NotificationDeliveryDispatcher deliveryDispatcher;

    public Optional<TwitchatWidgetAccessResponse> access(UUID uuid) {
        return widgetTokenService.resolve(uuid)
                .map(user -> new TwitchatWidgetAccessResponse(user.getId().toString(),
                        widgetSettingsRepository.findById(user.getId()).map(TwitchatWidgetSettings::isEnabled).orElse(false)));
    }

    public Optional<ConfigResponse> config(UUID uuid) {
        // Disabling the widget must also stop handing out the OBS-websocket password,
        // otherwise the "Activer" toggle revokes nothing (same check as access()).
        return widgetTokenService.resolve(uuid)
                .flatMap(user -> widgetSettingsRepository.findById(user.getId()))
                .filter(TwitchatWidgetSettings::isEnabled)
                .map(s -> new ConfigResponse(s.getObsHost(), s.getObsPort(),
                        s.getObsPasswordEncrypted() == null ? null : tokenEncryptionService.decrypt(s.getObsPasswordEncrypted()),
                        s.getTwitchatBranch()));
    }

    /** The widget's owner's pending Twitchat notifications, sent once catapult-web has the OBS widget connected. */
    public Optional<NotificationFlushResultDto> flush(UUID uuid) {
        // Like config(): a disabled widget is no widget, and its owner's deliveries stay pending
        return widgetTokenService.resolve(uuid)
                .filter(owner -> widgetSettingsRepository.findById(owner.getId()).map(TwitchatWidgetSettings::isEnabled).orElse(false))
                .map(owner -> new NotificationFlushResultDto(deliveryDispatcher.flushTwitchat(owner)));
    }
}
