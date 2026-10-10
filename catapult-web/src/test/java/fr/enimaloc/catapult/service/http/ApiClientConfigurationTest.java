package fr.enimaloc.catapult.service.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

/** ApiClient's base URL comes from the catapult.backend-url property, resolved by Spring. */
class ApiClientConfigurationTest {

    @Test
    void sendsRequestsToTheConfiguredBackend() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.test:8080/api/channels/enimaloc/settings/bot"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        new ApplicationContextRunner()
                .withPropertyValues("catapult.backend-url=http://api.test:8080")
                .withBean(RestClient.Builder.class, () -> builder)
                .withBean(ApiClient.class)
                .run(context -> context.getBean(ApiClient.class)
                        .postVoid("/api/channels/{username}/settings/bot", null, "enimaloc"));

        server.verify();
    }
}
