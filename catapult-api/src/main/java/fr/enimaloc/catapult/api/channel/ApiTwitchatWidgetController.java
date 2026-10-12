package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.common.dto.channel.ConfigResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/twitchat")
@RequiredArgsConstructor
public class ApiTwitchatWidgetController {

    private final TwitchatWidgetSettingsRepository widgetSettingsRepository;
    private final WidgetTokenService widgetTokenService;
    private final TokenEncryptionService tokenEncryptionService;
    private final TwitchatActionExecutor actionExecutor;

    @GetMapping("/widget/{uuid}/access")
    public ResponseEntity<TwitchatWidgetAccessResponse> access(@PathVariable UUID uuid) {
        return widgetTokenService.resolve(uuid)
                .map(user -> ResponseEntity.ok(new TwitchatWidgetAccessResponse(user.getId().toString(),
                        widgetSettingsRepository.findById(user.getId()).map(TwitchatWidgetSettings::isEnabled).orElse(false))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/widget/{uuid}")
    public ResponseEntity<ConfigResponse> config(@PathVariable UUID uuid) {
        // Disabling the widget must also stop handing out the OBS-websocket password,
        // otherwise the "Activer" toggle revokes nothing (same check as access()).
        return widgetTokenService.resolve(uuid)
                .flatMap(user -> widgetSettingsRepository.findById(user.getId()))
                .filter(TwitchatWidgetSettings::isEnabled)
                .map(s -> ResponseEntity.ok(new ConfigResponse(s.getObsHost(), s.getObsPort(),
                        s.getObsPasswordEncrypted() == null ? null : tokenEncryptionService.decrypt(s.getObsPasswordEncrypted()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/actions/{token}")
    public ResponseEntity<Map<String, String>> executeAction(@PathVariable UUID token) {
        TwitchatActionExecutor.Result result = actionExecutor.execute(token);
        return ResponseEntity.ok(Map.of("result", result.name()));
    }
}
