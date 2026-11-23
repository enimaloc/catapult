package fr.enimaloc.catapult.service.enrichment;

import fr.enimaloc.catapult.getter.steamgriddb.SteamGridDbApiKeyRotator;
import fr.enimaloc.catapult.service.enrichment.opencritic.HttpOpenCriticClient;
import fr.enimaloc.catapult.getter.opencritic.OpenCriticApiKeyRotator;
import fr.enimaloc.catapult.service.enrichment.prices.HttpItadClient;
import fr.enimaloc.catapult.service.enrichment.protondb.HttpProtonDbClient;
import fr.enimaloc.catapult.service.enrichment.steamdeck.HttpSteamDeckClient;
import fr.enimaloc.catapult.service.enrichment.steamgriddb.HttpSteamGridDbClient;
import fr.enimaloc.catapult.service.enrichment.twitch.HelixTwitchActivityClient;
import fr.enimaloc.catapult.getter.itad.ItadApiKeyRotator;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Every enrichment client that is built from the shared {@link RestClient.Builder} identifies Catapult, with the
 * same agent. (The Xbox, Steam stats, Speedrun, RetroAchievements, PCGamingWiki and Twitch token calls check theirs
 * in their own tests, their constructors taking a ready-made client.)
 */
class EnrichmentUserAgentTest {

    private static final String EXPECTED = "Catapult/1.0 (+https://catapult.enimaloc.fr)";

    private enum Client {
        PROTONDB(builder -> new HttpProtonDbClient(builder).fetchSummary("1")),
        STEAMDECK(builder -> new HttpSteamDeckClient(builder).fetch("1")),
        STEAMGRIDDB(builder -> {
            SteamGridDbApiKeyRotator rotator = mock(SteamGridDbApiKeyRotator.class);
            when(rotator.nextKey()).thenReturn(Optional.of("k"));
            new HttpSteamGridDbClient(builder, Optional.of(rotator)).fetchBySteamAppId("1");
        }),
        OPENCRITIC(builder -> {
            OpenCriticApiKeyRotator rotator = mock(OpenCriticApiKeyRotator.class);
            when(rotator.nextKey()).thenReturn(Optional.of("k"));
            new HttpOpenCriticClient(builder, Optional.of(rotator)).search("x");
        }),
        ITAD(builder -> {
            ItadApiKeyRotator rotator = mock(ItadApiKeyRotator.class);
            when(rotator.nextKey()).thenReturn(Optional.of("k"));
            new HttpItadClient(builder, Optional.of(rotator)).lookupBySteamAppId("1");
        }),
        TWITCH_HELIX(builder -> {
            TwitchTokenService tokens = mock(TwitchTokenService.class);
            when(tokens.getAppAccessToken()).thenReturn("tok");
            new HelixTwitchActivityClient(builder, tokens, "cid").categoryById("1");
        });

        private final Consumer<RestClient.Builder> call;

        Client(Consumer<RestClient.Builder> call) {
            this.call = call;
        }
    }

    @ParameterizedTest
    @EnumSource(Client.class)
    void everyRequestCarriesTheCatapultUserAgent(Client client) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(manyTimes(), anything())
                .andExpect(header(HttpHeaders.USER_AGENT, EXPECTED))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        try {
            client.call.accept(builder);
        } catch (RuntimeException e) {
            // the answer is of no interest, only the request: an assertion error would still fail the test
        }

        server.verify();
    }

    @Test
    void theAgentIsHonest_andFollowsTheBaseUrlWhenOneIsGiven() {
        assertThat(EnrichmentUserAgent.VALUE).isEqualTo(EXPECTED).doesNotContain("Mozilla");
        assertThat(EnrichmentUserAgent.of("https://catapult.example")).isEqualTo("Catapult/1.0 (+https://catapult.example)");
    }
}
