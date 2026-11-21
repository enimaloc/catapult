package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.api.userapi.GameInfoAssembler;
import fr.enimaloc.catapult.api.userapi.MoreLinks;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.service.enrichment.GameEnrichment;
import fr.enimaloc.catapult.service.enrichment.GameEnrichmentAssembler;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckCompatibility;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.simulation.account.FictitiousAccountService;
import fr.enimaloc.catapult.simulation.account.MockGuard;
import fr.enimaloc.catapult.simulation.account.MockLoginController;
import fr.enimaloc.catapult.simulation.panel.MockPanelApiController;
import fr.enimaloc.catapult.simulation.panel.MockPanelController;
import fr.enimaloc.catapult.simulation.twitch.SimulatedTwitchService;
import java.nio.file.Path;
import java.util.Optional;
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
        // application-dev.properties is local only (git-ignored): without it sentry.environment falls back to
        // production, which MockGuard refuses
        registry.add("sentry.environment", () -> "development");
        // full-context.properties turns the enrichment sources off; here the simulated clients (@Primary) answer
        // instead of the real ones, so nothing leaves the test
        for (String source : new String[]{"protondb", "steamdeck", "steamgriddb", "pcgamingwiki"}) {
            registry.add("catapult.enrichment." + source + ".enabled", () -> "true");
        }
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

    @Test
    void mockProfile_v3GameInfo_hasEnrichment() {
        GameEnrichment enrichment = context.getBean(GameEnrichmentAssembler.class).enrich(
                new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2"), Optional.empty(),
                Optional.of("570"), "Dota 2", "FR", null,
                new MoreLinks("http://localhost", GameInfoAssembler.LinkStyle.CURRENT), Optional.empty());

        assertThat(enrichment.protondb()).isNotNull();
        assertThat(enrichment.protondb().tier()).isEqualTo("gold");
        assertThat(enrichment.steamDeck()).isNotNull();
        assertThat(enrichment.steamDeck().category()).isEqualTo(SteamDeckCompatibility.Category.VERIFIED);
        assertThat(enrichment.steamgriddb()).isNotNull();
        assertThat(enrichment.steamgriddb().bestGrid().url())
                .isEqualTo("https://cdn2.steamgriddb.com/grid/simulated.png");
        assertThat(enrichment.pcgamingwiki()).isNotNull();
        assertThat(enrichment.pcgamingwiki().ultrawide()).isEqualTo("true");
    }
}
