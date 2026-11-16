package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.AppConfigResponse;
import fr.enimaloc.catapult.common.dto.LaunchedPlatformsResponse;
import fr.enimaloc.catapult.common.dto.ProvidersResponse;
import fr.enimaloc.catapult.getter.GameGetter;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.epic.EpicGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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

    private final AnkamaGateService ankamaGate;
    private final EpicGateService epicGate;
    private final BattleNetGateService battleNetGate;
    private final ExperimentService experiments;

    public ApiConfigController(List<GameGetter> availableGetters, Optional<SteamApiKeyRotator> rotator,
                               AnkamaGateService ankamaGate, EpicGateService epicGate,
                               BattleNetGateService battleNetGate, ExperimentService experiments) {
        this.availableGetters = availableGetters;
        this.rotator = rotator;
        this.ankamaGate = ankamaGate;
        this.epicGate = epicGate;
        this.battleNetGate = battleNetGate;
        this.experiments = experiments;
    }

    @GetMapping("/providers")
    public ProvidersResponse providers() {
        boolean showSteam = steamEnabled && steamKeyAvailable();
        return new ProvidersResponse(minecraftEnabled, showSteam, xboxEnabled, battlenetEnabled,
                ankamaGate.isConfigured(), epicGate.isConfigured());
    }

    @GetMapping("/app")
    public AppConfigResponse appConfig() {
        // distinct: under the simulation a getter and its simulated subclass are both beans
        List<String> getterNames = availableGetters.stream()
                .map(GameGetter::name)
                .distinct()
                .toList();
        return new AppConfigResponse(appName, deletionDelayDays, getterNames);
    }

    /**
     * The gated platforms open to everyone (the landing page lists them): configured, and their experiment fully
     * enabled. catapult-web keeps the answer a minute.
     */
    @GetMapping("/launched-platforms")
    public LaunchedPlatformsResponse launchedPlatforms() {
        List<String> platforms = new ArrayList<>();
        if (battleNetGate.isConfigured() && experiments.isFullyEnabled(BattleNetGateService.EXPERIMENT_KEY)) {
            platforms.add("battlenet");
        }
        if (ankamaGate.isConfigured() && experiments.isFullyEnabled(AnkamaGateService.EXPERIMENT_KEY)) {
            platforms.add("ankama");
        }
        if (epicGate.isConfigured() && experiments.isFullyEnabled(EpicGateService.EXPERIMENT_KEY)) {
            platforms.add("epic");
        }
        return new LaunchedPlatformsResponse(platforms);
    }

    private boolean steamKeyAvailable() {
        return rotator.map(r -> r.nextKey().isPresent()).orElseGet(() -> !steamApiKey.isBlank());
    }
}
