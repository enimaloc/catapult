package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.pcgw.HttpPcGamingWikiClient;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiClient;
import fr.enimaloc.catapult.service.enrichment.protondb.HttpProtonDbClient;
import fr.enimaloc.catapult.service.enrichment.protondb.ProtonDbClient;
import fr.enimaloc.catapult.service.enrichment.steamdeck.HttpSteamDeckClient;
import fr.enimaloc.catapult.service.enrichment.steamdeck.SteamDeckClient;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.HttpSteamGridDbClient;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.SteamGridDbClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which enrichment client a context resolves: the simulated one (primary) when the simulation is on, the HTTP one
 * otherwise. The HTTP clients are mocks and the simulated ones are scanned with their real conditions: only the
 * selection is under test.
 */
class SimulatedEnrichmentClientsWiringTest {

    @Configuration(proxyBeanMethods = false)
    static class HttpClients {
        @Bean HttpProtonDbClient protonDb() { return mock(HttpProtonDbClient.class); }
        @Bean HttpSteamDeckClient steamDeck() { return mock(HttpSteamDeckClient.class); }
        @Bean HttpPcGamingWikiClient pcgw() { return mock(HttpPcGamingWikiClient.class); }
        @Bean HttpSteamGridDbClient steamGridDb() { return mock(HttpSteamGridDbClient.class); }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SimulatedProtonDbClient.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*Simulated(ProtonDb|SteamDeck|PcGamingWiki|SteamGridDb)Client"))
    static class SimulatedClients {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HttpClients.class, SimulatedClients.class);

    @Test
    void simulationOn_selectsTheSimulatedClients() {
        runner.withPropertyValues("catapult.simulation.enabled=true").run(ctx -> {
            assertThat(ctx.getBean(ProtonDbClient.class)).isInstanceOf(SimulatedProtonDbClient.class);
            assertThat(ctx.getBean(SteamDeckClient.class)).isInstanceOf(SimulatedSteamDeckClient.class);
            assertThat(ctx.getBean(PcGamingWikiClient.class)).isInstanceOf(SimulatedPcGamingWikiClient.class);
            assertThat(ctx.getBean(SteamGridDbClient.class)).isInstanceOf(SimulatedSteamGridDbClient.class);
        });
    }

    @Test
    void simulationOff_keepsTheHttpClients() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(ProtonDbClient.class)).isInstanceOf(HttpProtonDbClient.class);
            assertThat(ctx.getBean(SteamDeckClient.class)).isInstanceOf(HttpSteamDeckClient.class);
            assertThat(ctx.getBean(PcGamingWikiClient.class)).isInstanceOf(HttpPcGamingWikiClient.class);
            assertThat(ctx.getBean(SteamGridDbClient.class)).isInstanceOf(HttpSteamGridDbClient.class);
        });
    }

    @Test
    void simulatedAnswers_needNoNetworkNorKey() {
        assertThat(new SimulatedProtonDbClient().fetchSummary("570")).isPresent();
        assertThat(new SimulatedSteamDeckClient().fetch("570")).isPresent();
        assertThat(new SimulatedPcGamingWikiClient().findByName("Dota 2")).isPresent();
        assertThat(new SimulatedSteamGridDbClient().fetchBySteamAppId("570")).get()
                .satisfies(a -> assertThat(a.bestGrid()).isNotNull());
    }
}
