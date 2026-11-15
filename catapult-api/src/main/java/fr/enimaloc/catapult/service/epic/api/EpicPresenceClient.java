package fr.enimaloc.catapult.service.epic.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Registers an Epic Connect connection with Epic's presence service so the bot appears online. */
@Component
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicPresenceClient {

    private static final String ONLINE_BODY = "{\"status\":\"online\",\"conn\":{\"props\":{}}}";

    private final RestClient client;
    private final String presenceUrl;

    public EpicPresenceClient(RestClient.Builder builder,
                              @Value("${epic.presence-url:https://api.epicgames.dev/epic/presence/internal/v1/_/}")
                              String presenceUrl) {
        this.client = builder.clone().build();
        this.presenceUrl = presenceUrl.endsWith("/") ? presenceUrl : presenceUrl + "/";
    }

    public void register(String token, String botId, String connectionId) {
        int status = client.post().uri(presenceUrl + "{bot}/presence/{conn}", botId, connectionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-HTTP-Method-Override", "PATCH")
                .contentType(MediaType.APPLICATION_JSON).body(ONLINE_BODY)
                .exchange((request, response) -> response.getStatusCode().value());
        if (status == 401) throw new EpicAuthException(EpicAuthException.Reason.ACCESS_REJECTED);
        if (status < 200 || status >= 300) {
            throw new RestClientException("Epic presence service answered HTTP " + status);
        }
    }
}
