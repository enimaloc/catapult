package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.simulation.account.FictitiousAccountService;
import fr.enimaloc.catapult.simulation.account.MockGuard;
import fr.enimaloc.catapult.simulation.account.MockLoginController;
import fr.enimaloc.catapult.simulation.panel.MockPanelApiController;
import fr.enimaloc.catapult.simulation.panel.MockPanelController;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The {@code mock} profile as it is run, along {@code dev}: the simulation layer, the panel and the fictitious login. */
@Import(FullContextContainers.class)
@ActiveProfiles({"dev", "mock"})
class CatapultApplicationMockTests extends FullContextTestBase {

    @TempDir
    static Path stateDir;

    @DynamicPropertySource
    static void stateFile(DynamicPropertyRegistry registry) {
        registry.add("catapult.simulation.state-file", () -> stateDir.resolve("simulation.json").toString());
    }

    @Test
    void loadsTheMockBeans() {
        assertThat(environment.getProperty("catapult.simulation.enabled")).isEqualTo("true");
        assertThat(context.getBeansOfType(MockGuard.class)).isNotEmpty();
        assertThat(context.getBeansOfType(FictitiousAccountService.class)).isNotEmpty();
        assertThat(context.getBeansOfType(MockLoginController.class)).isNotEmpty();
        assertThat(context.getBeansOfType(MockPanelController.class)).isNotEmpty();
        assertThat(context.getBeansOfType(MockPanelApiController.class)).isNotEmpty();
        assertThat(context.getBean(TwitchService.class)).isInstanceOf(SimulatedTwitchService.class);
    }
}
