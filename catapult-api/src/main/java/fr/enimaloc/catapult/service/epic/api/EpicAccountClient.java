package fr.enimaloc.catapult.service.epic.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/** Minimal client of Epic's OAuth token endpoint, with the launcher client, written from the observed protocol. */
@Component
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicAccountClient {

    static final String LAUNCHER_CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a";
    private static final String LAUNCHER_CLIENT_SECRET = "daafbccc737745039dffe53d94fc76cf";

    public record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt,
                         String accountId, String displayName) {
    }

    private record Reply(int status, JsonNode body) {
    }

    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String tokenUrl;
    private final Clock clock;

    @Autowired
    public EpicAccountClient(RestClient.Builder builder,
                             @Value("${epic.account-url:https://account-public-service-prod.ol.epicgames.com"
                                     + "/account/api/oauth/token}") String tokenUrl) {
        this(builder, tokenUrl, Clock.systemUTC());
    }

    EpicAccountClient(RestClient.Builder builder, String tokenUrl, Clock clock) {
        String basic = Base64.getEncoder().encodeToString(
                (LAUNCHER_CLIENT_ID + ":" + LAUNCHER_CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        this.client = builder.clone().defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build();
        this.tokenUrl = tokenUrl;
        this.clock = clock;
    }

    public Tokens exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("token_type", "eg1");
        return tokens(post(form));
    }

    public Tokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("token_type", "eg1");
        return tokens(post(form));
    }

    private Reply post(MultiValueMap<String, String> form) {
        return client.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .exchange(this::read);
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

    private Tokens tokens(Reply reply) {
        int status = reply.status();
        JsonNode body = reply.body();
        if (status >= 200 && status < 300) {
            Instant now = clock.instant();
            return new Tokens(text(body, "access_token"), now.plusSeconds(body.path("expires_in").asLong()),
                    text(body, "refresh_token"), now.plusSeconds(body.path("refresh_expires").asLong()),
                    text(body, "account_id"), text(body, "displayName"));
        }
        String errorCode = text(body, "errorCode");
        if (status == 400 && errorCode.endsWith("authorization_code_not_found")) {
            throw new EpicAuthException(EpicAuthException.Reason.CODE_EXPIRED);
        }
        if (status == 401 || status == 400 && (errorCode.endsWith("invalid_refresh_token")
                || "invalid_grant".equals(text(body, "error")))) {
            throw new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED);
        }
        throw new RestClientException("Epic account service answered HTTP " + status);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asString();
    }
}
