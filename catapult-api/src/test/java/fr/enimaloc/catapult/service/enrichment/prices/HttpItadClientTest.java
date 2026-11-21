package fr.enimaloc.catapult.service.enrichment.prices;

import fr.enimaloc.catapult.getter.itad.ItadApiKeyRotator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpItadClientTest {

    private static final String BASE = "https://api.isthereanydeal.com";
    private static final String ID = "018d937f-1d3b-7131-8a5b-3b0a3c8d0b57";

    private MockRestServiceServer server;
    private ItadApiKeyRotator rotator;
    private HttpItadClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rotator = mock(ItadApiKeyRotator.class);
        when(rotator.nextKey()).thenReturn(Optional.of("keyA"));
        client = new HttpItadClient(builder, Optional.of(rotator));
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("enrichment/itad/" + name + ".json").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void parsesLookupPricesAndHistoryLow() throws IOException {
        server.expect(once(), requestTo(BASE + "/games/lookup/v1?key=keyA&appid=570"))
                .andRespond(withSuccess(fixture("lookup"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/games/prices/v3?key=keyA&country=FR&deals=false"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("[\"" + ID + "\"]"))
                .andRespond(withSuccess(fixture("prices"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/games/historylow/v1?key=keyA&country=FR"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(fixture("historylow"), MediaType.APPLICATION_JSON));

        ItadGame game = client.lookupBySteamAppId("570").orElseThrow();
        GamePrices prices = client.prices(game, "FR").orElseThrow();

        assertThat(game).isEqualTo(new ItadGame(ID, "dota-2", "Dota 2"));
        assertThat(prices.currency()).isEqualTo("EUR");
        assertThat(prices.country()).isEqualTo("FR");
        assertThat(prices.itadUrl()).isEqualTo("https://isthereanydeal.com/game/dota-2/");
        assertThat(prices.deals()).extracting(GamePrices.Deal::shop).containsExactly("G2A", "Steam", "Epic Game Store");
        assertThat(prices.best()).isSameAs(prices.deals().get(0));
        assertThat(prices.best().shopId()).isEqualTo("35");
        assertThat(prices.best().price()).isEqualByComparingTo("5.00");
        assertThat(prices.best().regular()).isEqualByComparingTo("19.99");
        assertThat(prices.best().cut()).isEqualTo(75);
        assertThat(prices.best().url()).isEqualTo("https://itad.link/g2a-deal");
        assertThat(prices.historyLow().shopId()).isEqualTo("61");
        assertThat(prices.historyLow().shop()).isEqualTo("Steam");
        assertThat(prices.historyLow().price()).isEqualByComparingTo("4.99");
        assertThat(prices.historyLow().date()).isEqualTo(Instant.parse("2025-06-26T17:03:00Z"));
        verify(rotator, org.mockito.Mockito.times(3)).onCall("keyA");
        server.verify();
    }

    @Test
    void unknownSlug_noItadLink() throws IOException {
        server.expect(once(), requestTo(BASE + "/games/prices/v3?key=keyA&country=FR&deals=false"))
                .andRespond(withSuccess(fixture("prices"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/games/historylow/v1?key=keyA&country=FR"))
                .andRespond(withSuccess(fixture("historylow"), MediaType.APPLICATION_JSON));

        GamePrices prices = client.prices(new ItadGame(ID, null, "Dota 2"), "FR").orElseThrow();

        assertThat(prices.itadUrl()).isNull();
        assertThat(prices.best()).isNotNull();
    }

    @Test
    void infoReadsTheSlug() throws IOException {
        server.expect(once(), requestTo(BASE + "/games/info/v2?key=keyA&id=" + ID))
                .andRespond(withSuccess(fixture("info"), MediaType.APPLICATION_JSON));

        assertThat(client.info(ID)).contains(new ItadGame(ID, "dota-2", "Dota 2"));
    }

    @Test
    void lookupNotFound_isEmpty() {
        server.expect(once(), requestTo(BASE + "/games/lookup/v1?key=keyA&title=Nope"))
                .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.lookupByTitle("Nope")).isEmpty();
    }

    private static void assertNoSecret(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain("SECRET");
            assertThat(t.toString()).doesNotContain("SECRET");
        }
    }

    @Test
    void connectionFailure_neverLeaksTheKey() {
        when(rotator.nextKey()).thenReturn(Optional.of("SECRET"));
        server.expect(once(), requestTo(BASE + "/games/lookup/v1?key=SECRET&appid=570"))
                .andRespond(request -> {
                    throw new IOException("connect to " + request.getURI() + " failed");
                });

        assertThatThrownBy(() -> client.lookupBySteamAppId("570"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(HttpItadClientTest::assertNoSecret);
    }

    @Test
    void serverError_keepsTheStatusButNeverTheKey() {
        when(rotator.nextKey()).thenReturn(Optional.of("SECRET"));
        server.expect(once(), requestTo(BASE + "/games/lookup/v1?key=SECRET&appid=570"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("bad request key=SECRET"));

        assertThatThrownBy(() -> client.lookupBySteamAppId("570"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500")
                .satisfies(HttpItadClientTest::assertNoSecret);
    }

    @Test
    void rateLimitedKeyIsBlocked() {
        server.expect(once(), requestTo(BASE + "/games/lookup/v1?key=keyA&appid=570"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "30"));

        assertThatThrownBy(() -> client.lookupBySteamAppId("570"))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        verify(rotator).block("keyA", Duration.ofSeconds(30));
    }
}
