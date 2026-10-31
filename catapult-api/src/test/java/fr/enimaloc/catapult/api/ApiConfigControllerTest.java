package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.getter.GameGetter;
import fr.enimaloc.catapult.getter.steam.SteamApiKeyRotator;
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
        ApiConfigController controller = new ApiConfigController(List.of(), rotator);
        ReflectionTestUtils.setField(controller, "steamEnabled", true);
        ReflectionTestUtils.setField(controller, "steamApiKey", configuredKey);
        return controller;
    }

    @Test
    void appConfig_listsEachGetterNameOnce_inOrder() {
        // under the simulation a getter and its simulated subclass are both GameGetter beans
        ApiConfigController controller = new ApiConfigController(
                List.of(getter("Steam"), getter("Xbox"), getter("Minecraft"), getter("Xbox"), getter("Minecraft")),
                Optional.empty());

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
}
