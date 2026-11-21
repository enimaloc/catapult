package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.opencritic.HttpOpenCriticClient;
import fr.enimaloc.catapult.service.enrichment.opencritic.OpenCriticClient;
import fr.enimaloc.catapult.service.enrichment.pcgw.HttpPcGamingWikiClient;
import fr.enimaloc.catapult.service.enrichment.prices.HttpItadClient;
import fr.enimaloc.catapult.service.enrichment.prices.ItadClient;
import fr.enimaloc.catapult.service.enrichment.speedrun.HttpSpeedrunClient;
import fr.enimaloc.catapult.service.enrichment.speedrun.SpeedrunClient;
import fr.enimaloc.catapult.service.enrichment.twitch.HelixTwitchActivityClient;
import fr.enimaloc.catapult.service.enrichment.twitch.TwitchActivityClient;
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
        @Bean HttpItadClient itad() { return mock(HttpItadClient.class); }
        @Bean HttpOpenCriticClient openCritic() { return mock(HttpOpenCriticClient.class); }
        @Bean HttpSpeedrunClient speedrun() { return mock(HttpSpeedrunClient.class); }
        @Bean HelixTwitchActivityClient twitchActivity() { return mock(HelixTwitchActivityClient.class); }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SimulatedProtonDbClient.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = ".*Simulated(ProtonDb|SteamDeck|PcGamingWiki|SteamGridDb|Itad|OpenCritic|Speedrun|TwitchActivity)Client"))
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
            assertThat(ctx.getBean(ItadClient.class)).isInstanceOf(SimulatedItadClient.class);
            assertThat(ctx.getBean(OpenCriticClient.class)).isInstanceOf(SimulatedOpenCriticClient.class);
            assertThat(ctx.getBean(SpeedrunClient.class)).isInstanceOf(SimulatedSpeedrunClient.class);
            assertThat(ctx.getBean(TwitchActivityClient.class)).isInstanceOf(SimulatedTwitchActivityClient.class);
        });
    }

    @Test
    void simulationOff_keepsTheHttpClients() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(ProtonDbClient.class)).isInstanceOf(HttpProtonDbClient.class);
            assertThat(ctx.getBean(SteamDeckClient.class)).isInstanceOf(HttpSteamDeckClient.class);
            assertThat(ctx.getBean(PcGamingWikiClient.class)).isInstanceOf(HttpPcGamingWikiClient.class);
            assertThat(ctx.getBean(SteamGridDbClient.class)).isInstanceOf(HttpSteamGridDbClient.class);
            assertThat(ctx.getBean(ItadClient.class)).isInstanceOf(HttpItadClient.class);
            assertThat(ctx.getBean(OpenCriticClient.class)).isInstanceOf(HttpOpenCriticClient.class);
            assertThat(ctx.getBean(SpeedrunClient.class)).isInstanceOf(HttpSpeedrunClient.class);
            assertThat(ctx.getBean(TwitchActivityClient.class)).isInstanceOf(HelixTwitchActivityClient.class);
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

    @Test
    void simulatedNewSources_answerDeterministically() {
        var itad = new SimulatedItadClient();
        assertThat(itad.isConfigured()).isTrue();
        var game = itad.lookupBySteamAppId("570").orElseThrow();
        assertThat(itad.lookupByTitle("Dota 2")).isPresent();
        assertThat(itad.info(game.id())).isPresent();
        assertThat(itad.prices(game, "FR").orElseThrow()).satisfies(p -> {
            assertThat(p.currency()).isEqualTo("EUR");
            assertThat(p.deals()).hasSize(3);
            assertThat(p.best()).isEqualTo(p.deals().getFirst());
        });

        var oc = new SimulatedOpenCriticClient();
        assertThat(oc.isConfigured()).isTrue();
        var hit = oc.search("Dota 2").getFirst();
        assertThat(hit.name()).isEqualTo("Dota 2");
        assertThat(oc.game(hit.id()).orElseThrow().topCriticScore()).isEqualTo(88);

        var twitch = new SimulatedTwitchActivityClient();
        assertThat(twitch.isConfigured()).isTrue();
        var category = twitch.categoryByIgdbId("1").orElseThrow();
        assertThat(twitch.categoryById(category.id())).isPresent();
        assertThat(twitch.lives(category.id(), null).liveCount()).isEqualTo(42);
        assertThat(twitch.clips(category.id(), java.time.Instant.now()).top()).isNotEmpty();
    }

    @Test
    void simulatedSpeedrun_answersDeterministically() {
        var sr = new SimulatedSpeedrunClient();
        assertThat(sr.searchGames("Celeste").getFirst().name()).isEqualTo("Celeste");
        var records = sr.records("any").orElseThrow();
        assertThat(records.categories()).hasSize(3);
        assertThat(records.categories().getFirst().runners()).hasSize(2);
        var user = sr.usersByTwitch("simulated").getFirst();
        assertThat(sr.user("whoever")).contains(user);
        assertThat(sr.profile("valid")).contains(user);
        assertThat(sr.personalBests(user.id(), null)).hasSize(2).allSatisfy(r -> assertThat(r.place()).isNotNull());
        assertThat(sr.pendingRuns(user.id(), null, null)).hasSize(1)
                .allSatisfy(r -> assertThat(r.place()).isNull());
    }
}
