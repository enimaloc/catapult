package fr.enimaloc.catapult.service.epic.api;

import org.springframework.http.HttpRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** Reads an Epic reply as a status and a JSON body, without ever putting the body in an exception. */
final class EpicReplies {

    record Reply(int status, JsonNode body) {
        boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EpicReplies() {
    }

    /**
     * An empty body is an empty object. An unreadable body is tolerated on an error status (only the status
     * matters then) but is a failure on a success status, where the caller would act on made-up content.
     */
    static Reply read(HttpRequest request, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        byte[] raw = response.getBody().readAllBytes();
        boolean successful = status >= 200 && status < 300;
        try {
            return new Reply(status, raw.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(raw));
        } catch (RuntimeException e) {
            if (successful) throw new RestClientException("Epic returned an unreadable reply");
            return new Reply(status, MAPPER.createObjectNode());
        }
    }
}
