package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.getter.GameGetter;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public configuration endpoints consumed by catapult-web.
 * No authentication required — only exposes non-sensitive feature flags.
 */
@RestController
@RequestMapping("/api/config")
public class ApiConfigController {

    @Value("${steam.enabled:false}")         private boolean steamEnabled;
    @Value("${steam.api-key:}")              private String steamApiKey;
    @Value("${xbox.enabled:false}")          private boolean xboxEnabled;
    @Value("${battlenet.enabled:false}")     private boolean battlenetEnabled;
    @Value("${app.account.deletion-delay-days:7}") private int deletionDelayDays;
    @Value("${spring.application.name}")     private String appName;

    @Autowired(required = false)
    private SteamApiKeyRotator rotator;

    private final List<GameGetter> availableGetters;

    public ApiConfigController(List<GameGetter> availableGetters) {
        this.availableGetters = availableGetters;
    }

    @GetMapping("/providers")
    public ProvidersResponse providers() {
        boolean showSteam = steamEnabled && steamKeyAvailable();
        return new ProvidersResponse(showSteam, xboxEnabled, battlenetEnabled);
    }

    @GetMapping("/app")
    public AppConfigResponse appConfig() {
        List<String> getterNames = availableGetters.stream()
                .map(GameGetter::name)
                .toList();
        return new AppConfigResponse(appName, deletionDelayDays, getterNames);
    }

    private boolean steamKeyAvailable() {
        if (rotator != null) return rotator.nextKey().isPresent();
        return !steamApiKey.isBlank();
    }

    public record ProvidersResponse(boolean steam, boolean xbox, boolean battlenet) {
        public boolean hasAny() { return steam || xbox || battlenet; }
    }

    public record AppConfigResponse(String name, int deletionDelayDays, List<String> gettersName) {}
}
