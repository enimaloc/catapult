package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.security.CatapultOAuth2User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class HomeControllerTest {

    @InjectMocks
    HomeController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "steamEnabled", false);
        ReflectionTestUtils.setField(controller, "steamApiKey", "");
        ReflectionTestUtils.setField(controller, "xboxEnabled", false);
        ReflectionTestUtils.setField(controller, "battlenetEnabled", false);
    }

    @Test
    void home_withoutAuthentication_returnsHomeView() {
        Model model = new ExtendedModelMap();
        String view = controller.home(null, model);
        assertThat(view).isEqualTo("home");
    }

    @Test
    void home_withAuthenticatedUser_redirectsToApp() {
        CatapultOAuth2User principal = mock(CatapultOAuth2User.class);
        Model model = new ExtendedModelMap();
        String view = controller.home(principal, model);
        assertThat(view).isEqualTo("redirect:/app");
    }

    @Test
    void home_steamEnabledWithKey_showsSteam() {
        ReflectionTestUtils.setField(controller, "steamEnabled", true);
        ReflectionTestUtils.setField(controller, "steamApiKey", "somekey");

        Model model = new ExtendedModelMap();
        controller.home(null, model);

        assertThat(model.getAttribute("showSteam")).isEqualTo(true);
        assertThat(model.getAttribute("hasAnySources")).isEqualTo(true);
    }

    @Test
    void home_steamEnabledWithoutKey_hidesSteam() {
        ReflectionTestUtils.setField(controller, "steamEnabled", true);
        ReflectionTestUtils.setField(controller, "steamApiKey", "");

        Model model = new ExtendedModelMap();
        controller.home(null, model);

        assertThat(model.getAttribute("showSteam")).isEqualTo(false);
        assertThat(model.getAttribute("hasAnySources")).isEqualTo(false);
    }

    @Test
    void home_xboxEnabled_showsXbox() {
        ReflectionTestUtils.setField(controller, "xboxEnabled", true);

        Model model = new ExtendedModelMap();
        controller.home(null, model);

        assertThat(model.getAttribute("showXbox")).isEqualTo(true);
        assertThat(model.getAttribute("hasAnySources")).isEqualTo(true);
    }

    @Test
    void home_noProvidersEnabled_hasAnySourcesIsFalse() {
        Model model = new ExtendedModelMap();
        controller.home(null, model);

        assertThat(model.getAttribute("showSteam")).isEqualTo(false);
        assertThat(model.getAttribute("showXbox")).isEqualTo(false);
        assertThat(model.getAttribute("showBattlenet")).isEqualTo(false);
        assertThat(model.getAttribute("hasAnySources")).isEqualTo(false);
    }
}
