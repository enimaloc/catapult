package fr.enimaloc.catapult.service.enrichment.twitch;

import fr.enimaloc.catapult.service.enrichment.EnrichmentUserAgent;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@link TwitchActivityClient} over Twitch Helix with the app access token. Without a client id nothing is sent;
 * an empty token is a failure, never an absent category. A request Helix answers 401 invalidates the token and is
 * sent once more with a fresh one.
 */
@Component
public class HelixTwitchActivityClient implements TwitchActivityClient {

    private static final String BASE = "https://api.twitch.tv/helix";
    static final int MAX_PAGES = 5;
    static final int TOP = 10;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final TwitchTokenService tokens;
    private final String clientId;

    public HelixTwitchActivityClient(RestClient.Builder builder, TwitchTokenService tokens,
                                     @Value("${twitch.client-id:}") String clientId) {
        this.restClient = builder.defaultHeader(HttpHeaders.USER_AGENT, EnrichmentUserAgent.VALUE).build();
        this.tokens     = tokens;
        this.clientId   = clientId;
    }

    @Override
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank();
    }

    @Override
    public Optional<TwitchCategory> categoryByIgdbId(String igdbId) {
        return category(uri("/games", "igdb_id", igdbId));
    }

    @Override
    public Optional<TwitchCategory> categoryById(String id) {
        return category(uri("/games", "id", id));
    }

    @Override
    public TwitchLives lives(String gameId, String language) {
        int count = 0;
        long viewers = 0;
        boolean approximate = false;
        List<TwitchLives.Stream> all = new ArrayList<>();
        String cursor = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(BASE + "/streams")
                    .queryParam("game_id", gameId).queryParam("first", 100);
            if (language != null && !language.isBlank()) {
                b.queryParam("language", language);
            }
            if (cursor != null) {
                b.queryParam("after", cursor);
            }
            JsonNode json = get(b.build().encode().toUri());
            for (JsonNode s : json.path("data")) {
                int v = s.path("viewer_count").asInt(0);
                count++;
                viewers += v;
                String login = s.path("user_login").asString("");
                all.add(new TwitchLives.Stream(s.path("user_name").asString(login), login,
                        s.path("title").asString(""), v, s.path("language").asString(null),
                        "https://twitch.tv/" + login));
            }
            cursor = json.path("pagination").path("cursor").asString(null);
            if (cursor == null || cursor.isEmpty()) {
                break;
            }
            approximate = page == MAX_PAGES;
        }
        List<TwitchLives.Stream> top = all.stream()
                .sorted(Comparator.comparingInt(TwitchLives.Stream::viewers).reversed()).limit(TOP).toList();
        return new TwitchLives(count, viewers, approximate, top);
    }

    @Override
    public TwitchClips clips(String gameId, Instant since) {
        URI uri = UriComponentsBuilder.fromUriString(BASE + "/clips").queryParam("game_id", gameId)
                .queryParam("started_at", since.toString()).queryParam("first", TOP).build().encode().toUri();
        List<TwitchClips.Clip> clips = new ArrayList<>();
        for (JsonNode c : get(uri).path("data")) {
            clips.add(new TwitchClips.Clip(c.path("title").asString(""), c.path("broadcaster_name").asString(""),
                    c.path("view_count").asInt(0), c.path("duration").asDouble(0), c.path("url").asString(""),
                    c.path("thumbnail_url").asString(null)));
        }
        return new TwitchClips(clips);
    }

    private Optional<TwitchCategory> category(URI uri) {
        JsonNode data = get(uri).path("data");
        if (!data.isArray() || data.isEmpty() || !data.get(0).hasNonNull("id")) {
            return Optional.empty();
        }
        return Optional.of(new TwitchCategory(data.get(0).get("id").asString(), data.get(0).path("name").asString("")));
    }

    private static URI uri(String path, String param, String value) {
        return UriComponentsBuilder.fromUriString(BASE + path).queryParam(param, value).build().encode().toUri();
    }

    private String tokenOrFail() {
        if (!isConfigured()) {
            throw new IllegalStateException("Twitch is not configured");
        }
        String token = tokens.getAppAccessToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Twitch is not configured: no app access token");
        }
        return token;
    }

    /** {@code uri} with the app token; on a 401 the token is invalidated and the call retried once. */
    private JsonNode get(URI uri) {
        String token = tokenOrFail();
        String body;
        try {
            body = send(uri, token);
        } catch (HttpClientErrorException.Unauthorized e) {
            tokens.invalidateAppAccessToken(token);
            body = send(uri, tokenOrFail());
        }
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private String send(URI uri, String token) {
        return restClient.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Client-Id", clientId)
                .retrieve().body(String.class);
    }
}
