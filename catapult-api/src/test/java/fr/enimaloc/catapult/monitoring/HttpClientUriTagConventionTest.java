package fr.enimaloc.catapult.monitoring;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientUriTagConventionTest {

    private final HttpClientUriTagConvention convention = new HttpClientUriTagConvention();

    /*
     * The Helix calls put the streamer's id in the query ("/helix/channels?broadcaster_id=" + id): one uri tag
     * per streamer, until Micrometer's cap (100) drops every new uri, seen in production.
     */
    @Test
    void theQueryString_isLeftOutOfTheUriTag() {
        assertThat(uriTag("https://api.twitch.tv/helix/channels?broadcaster_id=12345"))
                .isEqualTo("/helix/channels");
        assertThat(uriTag("https://api.twitch.tv/helix/games?name={name}")).isEqualTo("/helix/games");
    }

    @Test
    void aTemplateWithoutQuery_isTaggedAsBefore() {
        assertThat(uriTag("https://api.twitch.tv/helix/users/extensions")).isEqualTo("/helix/users/extensions");
        assertThat(uriTag("campaigns/{id}/")).isEqualTo("/campaigns/{id}/");
    }

    @Test
    void aRequestWithoutTemplate_isTaggedNone() {
        assertThat(uriTag(null)).isEqualTo(KeyValue.NONE_VALUE);
    }

    @Test
    void keepsSpringsObservationName() {
        assertThat(convention.getName()).isEqualTo("http.client.requests");
    }

    private String uriTag(String template) {
        ClientRequestObservationContext context = new ClientRequestObservationContext(
                new MockClientHttpRequest(HttpMethod.GET, "https://api.twitch.tv/helix/channels"));
        context.setUriTemplate(template);
        return convention.getLowCardinalityKeyValues(context).stream()
                .filter(keyValue -> keyValue.getKey().equals("uri"))
                .findFirst().orElseThrow().getValue();
    }
}
