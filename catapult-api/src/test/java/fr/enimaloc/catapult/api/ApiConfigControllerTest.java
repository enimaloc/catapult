package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.getter.GameGetter;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
import fr.enimaloc.catapult.service.ankama.AnkamaGateService;
import fr.enimaloc.catapult.service.epic.EpicGateService;
import fr.enimaloc.catapult.service.battlenet.BattleNetGateService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Steam is offered when it is enabled and a key is usable: the pool's when there is one, else the configured key. */
class ApiConfigControllerTest {

    private static ApiConfigController controller(Optional<SteamApiKeyRotator> rotator, String configuredKey) {
        ApiConfigController controller = new ApiConfigController(List.of(), rotator, mock(AnkamaGateService.class),
                mock(EpicGateService.class), mock(BattleNetGateService.class), mock(ExperimentService.class));
        ReflectionTestUtils.setField(controller, "steamEnabled", true);
        ReflectionTestUtils.setField(controller, "steamApiKey", configuredKey);
        return controller;
    }

    @Test
    void appConfig_listsEachGetterNameOnce_inOrder() {
        // under the simulation a getter and its simulated subclass are both GameGetter beans
        ApiConfigController controller = new ApiConfigController(
                List.of(getter("Steam"), getter("Xbox"), getter("Minecraft"), getter("Xbox"), getter("Minecraft")),
                Optional.empty(), mock(AnkamaGateService.class), mock(EpicGateService.class),
                mock(BattleNetGateService.class), mock(ExperimentService.class));

        assertThat(controller.appConfig().gettersName()).containsExactly("Steam", "Xbox", "Minecraft");
    }

    private static GameGetter getter(String name) {
        GameGetter getter = mock(GameGetter.class);
        when(getter.name()).thenReturn(name);
        return getter;
    }

    @Test
    void providers_withoutRotator_steamFollowsTheConfiguredKey() {
        assertThat(controller(Optional.empty(), "KEY").providers().steam()).isTrue();
        assertThat(controller(Optional.empty(), "").providers().steam()).isFalse();
    }

    @Test
    void providers_withRotator_steamFollowsThePool() {
        SteamApiKeyRotator rotator = mock(SteamApiKeyRotator.class);
        when(rotator.nextKey()).thenReturn(Optional.of("POOLED"), Optional.empty());
        ApiConfigController controller = controller(Optional.of(rotator), "");

        assertThat(controller.providers().steam()).isTrue();
        assertThat(controller.providers().steam()).isFalse();
    }

    @Test
    void providers_ankamaFollowsTheGate() {
        AnkamaGateService gate = mock(AnkamaGateService.class);
        when(gate.isConfigured()).thenReturn(true, false);
        ApiConfigController controller = new ApiConfigController(List.of(), Optional.empty(), gate,
                mock(EpicGateService.class), mock(BattleNetGateService.class), mock(ExperimentService.class));

        assertThat(controller.providers().ankama()).isTrue();
        assertThat(controller.providers().ankama()).isFalse();
    }

    @Test
    void providers_epicFollowsTheGate() {
        EpicGateService gate = mock(EpicGateService.class);
        when(gate.isConfigured()).thenReturn(true, false);
        ApiConfigController controller = new ApiConfigController(List.of(), Optional.empty(),
                mock(AnkamaGateService.class), gate, mock(BattleNetGateService.class), mock(ExperimentService.class));

        assertThat(controller.providers().epic()).isTrue();
        assertThat(controller.providers().epic()).isFalse();
    }

    /** The landing page lists Battle.net and Ankama once they are configured and their experiment is fully enabled. */
    @Test
    void launchedPlatforms_needTheProviderConfigured_andItsExperimentFullyEnabled() {
        AnkamaGateService ankama = mock(AnkamaGateService.class);
        BattleNetGateService battleNet = mock(BattleNetGateService.class);
        ExperimentService experiments = mock(ExperimentService.class);
        EpicGateService epic = mock(EpicGateService.class);
        ApiConfigController controller = new ApiConfigController(List.of(), Optional.empty(), ankama, epic,
                battleNet, experiments);

        when(battleNet.isConfigured()).thenReturn(true);
        when(ankama.isConfigured()).thenReturn(true);
        when(experiments.isFullyEnabled(BattleNetGateService.EXPERIMENT_KEY)).thenReturn(true);
        when(experiments.isFullyEnabled(AnkamaGateService.EXPERIMENT_KEY)).thenReturn(true);
        assertThat(controller.launchedPlatforms().platforms()).containsExactly("battlenet", "ankama");

        when(epic.isConfigured()).thenReturn(true);
        when(experiments.isFullyEnabled(EpicGateService.EXPERIMENT_KEY)).thenReturn(true);
        assertThat(controller.launchedPlatforms().platforms()).containsExactly("battlenet", "ankama", "epic");
        when(epic.isConfigured()).thenReturn(false);

        when(experiments.isFullyEnabled(AnkamaGateService.EXPERIMENT_KEY)).thenReturn(false);
        assertThat(controller.launchedPlatforms().platforms()).containsExactly("battlenet");

        when(battleNet.isConfigured()).thenReturn(false);
        assertThat(controller.launchedPlatforms().platforms()).isEmpty();
    }
}
