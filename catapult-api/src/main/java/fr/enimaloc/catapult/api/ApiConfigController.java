package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.AppConfigResponse;
import fr.enimaloc.catapult.common.dto.ProvidersResponse;
import fr.enimaloc.catapult.getter.GameGetter;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Public configuration endpoints consumed by catapult-web.
 * No authentication required — only exposes non-sensitive feature flags.
 */
@RestController
@RequestMapping(ApiPaths.INTERNAL + "/config")
public class ApiConfigController {

    @Value("${minecraft.enabled:false}")         private boolean minecraftEnabled;
    @Value("${steam.enabled:false}")         private boolean steamEnabled;
    @Value("${steam.api-key:}")              private String steamApiKey;
    @Value("${xbox.enabled:false}")          private boolean xboxEnabled;
    @Value("${battlenet.enabled:false}")     private boolean battlenetEnabled;
    @Value("${app.account.deletion-delay-days:7}") private int deletionDelayDays;
    @Value("${spring.application.name}")     private String appName;

    private final List<GameGetter> availableGetters;
    private final Optional<SteamApiKeyRotator> rotator;

    public ApiConfigController(List<GameGetter> availableGetters, Optional<SteamApiKeyRotator> rotator) {
        this.availableGetters = availableGetters;
        this.rotator = rotator;
    }

    @GetMapping("/providers")
    public ProvidersResponse providers() {
        boolean showSteam = steamEnabled && steamKeyAvailable();
        return new ProvidersResponse(minecraftEnabled, showSteam, xboxEnabled, battlenetEnabled);
    }

    @GetMapping("/app")
    public AppConfigResponse appConfig() {
        List<String> getterNames = availableGetters.stream()
                .map(GameGetter::name)
                .toList();
        return new AppConfigResponse(appName, deletionDelayDays, getterNames);
    }

    private boolean steamKeyAvailable() {
        return rotator.map(r -> r.nextKey().isPresent()).orElseGet(() -> !steamApiKey.isBlank());
    }
}
