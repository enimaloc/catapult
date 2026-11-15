package fr.enimaloc.catapult.service.epic.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Minimal client of Epic's friends service, written from the observed protocol. */
@Component
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicFriendsClient {

    public enum AddResult { SENT, ALREADY_FRIENDS }

    public record Summary(Set<String> friends, Set<String> incoming, Set<String> outgoing) {
        public Summary {
            friends = Set.copyOf(friends);
            incoming = Set.copyOf(incoming);
            outgoing = Set.copyOf(outgoing);
        }
    }

    private record Reply(int status, JsonNode body) {
    }

    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String friendsUrl;

    public EpicFriendsClient(RestClient.Builder builder,
                             @Value("${epic.friends-url:https://friends-public-service-prod.ol.epicgames.com"
                                     + "/friends/api/v1}") String friendsUrl) {
        this.client = builder.clone().build();
        this.friendsUrl = friendsUrl.endsWith("/") ? friendsUrl.substring(0, friendsUrl.length() - 1) : friendsUrl;
    }

    public AddResult add(String token, String botId, String accountId) {
        Reply reply = client.post().uri(friendsUrl + "/{bot}/friends/{id}", botId, accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange(this::read);
        int status = reply.status();
        if (status >= 200 && status < 300) return AddResult.SENT;
        if (status == 409 && reply.body().path("errorCode").asString("").endsWith("duplicate_friendship")) {
            return AddResult.ALREADY_FRIENDS;
        }
        throw failure(status);
    }

    public void delete(String token, String botId, String accountId) {
        Reply reply = client.delete().uri(friendsUrl + "/{bot}/friends/{id}", botId, accountId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange(this::read);
        int status = reply.status();
        if (status >= 200 && status < 300 || status == 404) return;
        throw failure(status);
    }

    public Summary summary(String token, String botId) {
        Reply reply = client.get().uri(friendsUrl + "/{bot}/summary", botId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange(this::read);
        int status = reply.status();
        if (status < 200 || status >= 300) throw failure(status);
        return toSummary(reply.body());
    }

    /** The only place that knows the shape of the summary document (to be confirmed against the live service). */
    private static Summary toSummary(JsonNode body) {
        return new Summary(accountIds(body.path("friends")), accountIds(body.path("incoming")),
                accountIds(body.path("outgoing")));
    }

    private static Set<String> accountIds(JsonNode list) {
        Set<String> ids = new HashSet<>();
        list.forEach(entry -> {
            String id = entry.path("accountId").asString("");
            if (!id.isBlank()) ids.add(id);
        });
        return ids;
    }

    private Reply read(org.springframework.http.HttpRequest request,
                       RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        byte[] raw = response.getBody().readAllBytes();
        JsonNode body;
        try {
            body = raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (RuntimeException e) {
            body = mapper.createObjectNode();
        }
        return new Reply(status, body);
    }

    private static RuntimeException failure(int status) {
        if (status == 401) return new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED);
        return new RestClientException("Epic friends service answered HTTP " + status);
    }
}
