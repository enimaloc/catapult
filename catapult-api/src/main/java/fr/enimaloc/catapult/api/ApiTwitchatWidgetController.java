package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/twitchat")
@RequiredArgsConstructor
public class ApiTwitchatWidgetController {

    private final TwitchatWidgetSettingsRepository widgetSettingsRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final TwitchatActionExecutor actionExecutor;
    private final MessageSource messageSource;

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

    @org.springframework.beans.factory.annotation.Value("${app.web-url:http://localhost:8081}")
    private String publicWebUrl;

    @GetMapping("/defaults")
    public Map<String, DefaultPayloadResponse> defaults() {
        Map<String, DefaultPayloadResponse> result = new java.util.LinkedHashMap<>();
        String base = stripTrailingSlash(publicWebUrl);
        for (var entry : fr.enimaloc.catapult.service.notification.TwitchatDefaultPayloads.DEFAULTS.entrySet()) {
            var d = entry.getValue();
            java.util.List<DefaultActionResponse> actions = new java.util.ArrayList<>();
            d.actions().forEach((type, def) -> actions.add(new DefaultActionResponse(def.label(), "url",
                    base + "/widget/twitchat/action/{{action:" + type.name() + "}}", def.theme())));
            result.put(entry.getKey().name(),
                    new DefaultPayloadResponse(d.message(), d.style(), d.icon(), d.authorName(), actions));
        }
        return result;
    }

    @GetMapping("/quick-configs")
    public java.util.List<fr.enimaloc.catapult.service.notification.dto.TwitchatQuickConfig> quickConfigs(Locale locale) {
        String base = stripTrailingSlash(publicWebUrl);
        return fr.enimaloc.catapult.service.notification.TwitchatQuickConfigs.resolve(messageSource, locale).stream()
                .map(qc -> new fr.enimaloc.catapult.service.notification.dto.TwitchatQuickConfig(
                        qc.key(), qc.label(), qc.description().replace("{{baseUrl}}", base), qc.eventType(),
                        qc.parameters(), qc.templateJson().replace("{{baseUrl}}", base)))
                .toList();
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record DefaultPayloadResponse(String message, String style, String icon, String authorName,
                                          java.util.List<DefaultActionResponse> actions) {}
    public record DefaultActionResponse(String label, String actionType, String url, String theme) {}
}
