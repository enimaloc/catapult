package fr.enimaloc.catapult.service.epic.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EpicCatalogApiTest {

    private static final String URL = "https://catalog.test/bulk/offers";

    private MockRestServiceServer server;
    private EpicCatalogApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        api = new EpicCatalogApi(builder, URL);
    }

    @Test
    void priceTiers_batchesTheIds_inOneCall() {
        server.expect(requestTo(URL + "?id=o1&id=o2&id=missing&returnItemDetails=false&country=US&locale=en"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("""
                        {"o1":{"id":"o1","namespace":"ns","title":"Fall Guys",
                               "priceTier":"d42d7101bb7a41c09a810ee418b7d3d0_USD_0_1652474884058"},
                         "o2":{"id":"o2","title":"No tier"}}
                        """, MediaType.APPLICATION_JSON));

        Map<String, String> tiers = api.priceTiers("tok", List.of("o1", "o2", "missing"));

        assertThat(tiers).containsExactly(Map.entry("o1", "d42d7101bb7a41c09a810ee418b7d3d0_USD_0_1652474884058"));
        server.verify();
    }

    @Test
    void priceTiers_emptyInputMakesNoCall() {
        assertThat(api.priceTiers("tok", List.of())).isEmpty();
        server.verify();
    }
}
