package fr.enimaloc.catapult.service.epic.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EpicPresenceClientTest {

    private static final String URL = "https://presence.test/v1/_/";

    private MockRestServiceServer server;
    private EpicPresenceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EpicPresenceClient(builder, URL);
    }

    @Test
    void register_sendsThePatchOverride_andTheOnlineBody() {
        server.expect(requestTo(URL + "bot/presence/conn-1")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-HTTP-Method-Override", "PATCH"))
                .andExpect(header("Authorization", "Bearer eos"))
                .andExpect(content().json("{\"status\":\"online\",\"conn\":{\"props\":{}}}"))
                .andRespond(withSuccess("{\"own\":{}}", MediaType.APPLICATION_JSON));

        client.register("eos", "bot", "conn-1");

        server.verify();
    }

    @Test
    void register_failureThrows() {
        server.expect(requestTo(URL + "bot/presence/conn-1")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.register("eos", "bot", "conn-1"))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .hasMessageNotContaining("eos");
    }
}
