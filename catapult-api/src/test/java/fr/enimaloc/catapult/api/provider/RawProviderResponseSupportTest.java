package fr.enimaloc.catapult.api.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;

class RawProviderResponseSupportTest {

    private final RawProviderResponseSupport support = new RawProviderResponseSupport(new ObjectMapper());

    @Test
    void fetch_success_prettyPrintsJsonAndReturns200() {
        var result = support.fetch(() -> "{\"id\":42,\"name\":\"jeb_\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.error()).isNull();
        assertThat(result.hasError()).isFalse();
        assertThat(result.body()).contains("\"id\" : 42").contains("\"name\" : \"jeb_\"");
    }

    @Test
    void fetch_httpClientError_capturesStatusAndResponseBody() {
        var result = support.fetch(() -> {
            throw HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND, "Not Found", null,
                    "{\"message\":\"user not found\"}".getBytes(), null);
        });

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.body()).isNull();
        assertThat(result.error()).contains("user not found");
        assertThat(result.hasError()).isTrue();
    }

    @Test
    void fetch_unexpectedException_returns502WithMessage() {
        var result = support.fetch(() -> {
            throw new IllegalStateException("connection reset");
        });

        assertThat(result.status()).isEqualTo(502);
        assertThat(result.body()).isNull();
        assertThat(result.error()).isEqualTo("connection reset");
    }

    @Test
    void fetch_malformedJson_returns502() {
        var result = support.fetch(() -> "not json at all {");

        assertThat(result.status()).isEqualTo(502);
        assertThat(result.body()).isNull();
        assertThat(result.error()).isNotBlank();
    }
}
