package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
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
    private final TokenEncryptionService tokenEncryptionService;
    private final TwitchatActionExecutor actionExecutor;

    public record AccessResponse(String ownerId, boolean enabled) {}
    public record ConfigResponse(String obsHost, Integer obsPort, String obsPassword) {}

    @GetMapping("/widget/{token}/access")
    public ResponseEntity<AccessResponse> access(@PathVariable UUID token) {
        return widgetSettingsRepository.findByWidgetToken(token)
                .map(s -> ResponseEntity.ok(new AccessResponse(s.getUser().getId().toString(), s.isEnabled())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/widget/{token}")
    public ResponseEntity<ConfigResponse> config(@PathVariable UUID token) {
        // Disabling the widget must also stop handing out the OBS-websocket password,
        // otherwise the "Activer" toggle revokes nothing (same check as access()).
        return widgetSettingsRepository.findByWidgetToken(token)
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
