package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.common.dto.ConfigResponse;
import fr.enimaloc.catapult.common.dto.DefaultActionResponse;
import fr.enimaloc.catapult.common.dto.DefaultPayloadResponse;
import fr.enimaloc.catapult.common.dto.TwitchatQuickConfig;
import fr.enimaloc.catapult.common.dto.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import fr.enimaloc.catapult.service.notification.TwitchatActionExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
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
    private final MessageSource messageSource;

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

    @org.springframework.beans.factory.annotation.Value("${app.web-url:http://localhost:8081}")
    private String publicWebUrl;

    @GetMapping("/defaults")
    public Map<String, DefaultPayloadResponse> defaults() {
        Map<String, DefaultPayloadResponse> result = new LinkedHashMap<>();
        String base = stripTrailingSlash(publicWebUrl);
        for (var entry : fr.enimaloc.catapult.service.notification.TwitchatDefaultPayloads.DEFAULTS.entrySet()) {
            var d = entry.getValue();
            List<DefaultActionResponse> actions = new java.util.ArrayList<>();
            d.actions().forEach((type, def) -> actions.add(new DefaultActionResponse(def.label(), "url",
                    base + "/widget/twitchat/action/{{action:" + type.name() + "}}", def.theme())));
            result.put(entry.getKey().name(),
                    new DefaultPayloadResponse(d.message(), d.style(), d.icon(), d.authorName(), actions));
        }
        return result;
    }

    @GetMapping("/quick-configs")
    public List<TwitchatQuickConfig> quickConfigs(Locale locale) {
        String base = stripTrailingSlash(publicWebUrl);
        return fr.enimaloc.catapult.service.notification.TwitchatQuickConfigs.resolve(messageSource, locale).stream()
                .map(qc -> new TwitchatQuickConfig(
                        qc.key(), qc.label(), qc.description().replace("{{baseUrl}}", base), qc.eventType(),
                        qc.groupKey(), qc.groupLabel(), qc.variant(),
                        qc.parameters(), qc.templateJson().replace("{{baseUrl}}", base)))
                .toList();
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
