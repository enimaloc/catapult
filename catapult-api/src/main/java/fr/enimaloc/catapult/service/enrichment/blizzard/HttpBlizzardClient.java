package fr.enimaloc.catapult.service.enrichment.blizzard;

import fr.enimaloc.catapult.service.enrichment.EnrichmentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@link BlizzardClient} over {@code https://<region>.api.blizzard.com}. The application token only travels in the
 * {@code Authorization} header (as a query parameter Blizzard answers 404) and no token or body is ever put in an
 * exception message or a log. Redirects are never followed and any 3xx is an error; a 2xx that is not JSON or not of
 * the expected shape is an error, never "absent"; only the recognised JSON 404 bodies mean "not found". Every
 * request, retries included, asks the {@link BlizzardRateLimiter} first.
 */
@Component
public class HttpBlizzardClient implements BlizzardClient {

    static final String USER_AGENT = "Catapult/1.0 (+https://catapult.enimaloc.fr)";
    private static final Duration PAUSE = Duration.ofSeconds(60);
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ACHIEVEMENT_BYTES = 8 * 1024 * 1024;
    private static final int RECENT = 10;

    private record Answer(int status, String retryAfter, byte[] body, boolean tooBig) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient restClient;
    private final BlizzardAppTokenService tokens;
    private final BlizzardRateLimiter limiter;
    private final BlizzardRegion defaultRegion;

    @Autowired
    public HttpBlizzardClient(RestClient.Builder builder, BlizzardAppTokenService tokens,
                              BlizzardRateLimiter limiter, EnrichmentProperties properties) {
        this(builder.requestFactory(requestFactory()).build(), tokens, limiter, properties.blizzardDefaultRegion());
    }

    HttpBlizzardClient(RestClient restClient, BlizzardAppTokenService tokens, BlizzardRateLimiter limiter,
                       BlizzardRegion defaultRegion) {
        this.restClient = restClient;
        this.tokens = tokens;
        this.limiter = limiter;
        this.defaultRegion = defaultRegion;
    }

    /** The transport: never follows a redirect, 5 s to connect, 10 s to read. */
    static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static ClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    // --- WoW ---

    @Override
    public WowCharacter wowCharacter(BlizzardRegion region, String realmSlug, String name, String locale) {
        String base = region.host() + "/profile/wow/character/{realm}/{name}";
        String query = "namespace={ns}&locale={locale}";
        String lower = name.toLowerCase(Locale.ROOT);
        Object[] vars = {realmSlug, lower, region.profileNamespace(), locale};
        JsonNode profile = object(get(base + "?" + query, MAX_BYTES, wowNotFound(), vars));
        if (!profile.path("id").isNumber() || !profile.path("name").isString()) {
            throw garbled();
        }
        Double rating = null;
        JsonNode mythic = secondary(base + "/mythic-keystone-profile?" + query, vars);
        if (mythic != null && mythic.path("current_mythic_rating").path("rating").isNumber()) {
            rating = mythic.get("current_mythic_rating").get("rating").asDouble();
        }
        String avatar = null;
        JsonNode media = secondary(base + "/character-media?" + query, vars);
        if (media != null) {
            for (JsonNode asset : media.path("assets")) {
                if ("avatar".equals(text(asset.path("key")))) {
                    avatar = text(asset.path("value"));
                }
            }
        }
        JsonNode realm = profile.path("realm");
        return new WowCharacter(profile.get("id").asLong(), profile.get("name").asString(), text(realm.path("slug")),
                text(realm.path("name")), profile.path("level").asInt(0), text(profile.path("character_class").path("name")),
                text(profile.path("active_spec").path("name")), text(profile.path("race").path("name")),
                text(profile.path("faction").path("name")), text(profile.path("guild").path("name")),
                profile.path("achievement_points").asInt(0), integer(profile.path("average_item_level")),
                integer(profile.path("equipped_item_level")),
                profile.path("last_login_timestamp").isNumber()
                        ? Instant.ofEpochMilli(profile.get("last_login_timestamp").asLong()) : null,
                rating, avatar);
    }

    @Override
    public WowAchievements wowAchievements(BlizzardRegion region, String realmSlug, String name, String locale) {
        JsonNode json = object(get(region.host() + "/profile/wow/character/{realm}/{name}/achievements"
                        + "?namespace={ns}&locale={locale}", MAX_ACHIEVEMENT_BYTES, wowNotFound(), realmSlug,
                name.toLowerCase(Locale.ROOT), region.profileNamespace(), locale));
        if (!json.path("total_points").isNumber()) {
            throw garbled();
        }
        List<WowAchievements.Recent> recent = new ArrayList<>();
        for (JsonNode event : json.path("recent_events")) {
            JsonNode achievement = event.path("achievement");
            if (achievement.path("id").isNumber() && event.path("timestamp").isNumber()) {
                recent.add(new WowAchievements.Recent(String.valueOf(achievement.get("id").asLong()),
                        text(achievement.path("name")), Instant.ofEpochMilli(event.get("timestamp").asLong())));
            }
        }
        recent.sort(Comparator.comparing(WowAchievements.Recent::unlockedAt).reversed());
        return new WowAchievements(json.get("total_points").asInt(), recent.subList(0, Math.min(RECENT, recent.size())));
    }

    @Override
    public List<WowUserCharacter> wowUserCharacters(BlizzardRegion region, String userAccessToken) {
        Answer answer = send(region.host() + "/profile/user/wow?namespace={ns}", userAccessToken, MAX_BYTES,
                region.profileNamespace());
        JsonNode json;
        try {
            json = object(interpret(answer, wowNotFound()));
        } catch (BlizzardNotFoundException e) {
            return List.of();
        }
        if (!json.path("wow_accounts").isArray()) {
            throw garbled();
        }
        List<WowUserCharacter> characters = new ArrayList<>();
        for (JsonNode account : json.get("wow_accounts")) {
            if (!account.path("characters").isArray()) {
                throw garbled();
            }
            for (JsonNode character : account.get("characters")) {
                if (!character.path("id").isNumber() || !character.path("name").isString()) {
                    throw garbled();
                }
                characters.add(new WowUserCharacter(character.get("id").asLong(), character.get("name").asString(),
                        text(character.path("realm").path("slug")), text(character.path("realm").path("name")),
                        text(character.path("playable_class").path("name")), text(character.path("faction").path("name")),
                        character.path("level").asInt(0)));
            }
        }
        return characters;
    }

    @Override
    public WowTokenPrice wowToken(BlizzardRegion region) {
        JsonNode json = object(get(region.host() + "/data/wow/token/index?namespace={ns}", MAX_BYTES, none(),
                region.dynamicNamespace()));
        if (!json.path("price").isNumber()) {
            throw garbled();
        }
        return new WowTokenPrice(json.get("price").asLong() / 10_000L,
                json.path("last_updated_timestamp").isNumber()
                        ? Instant.ofEpochMilli(json.get("last_updated_timestamp").asLong()) : null);
    }

    // --- StarCraft II ---

    @Override
    public List<Sc2ProfileRef> sc2Profiles(long bnetAccountId) {
        JsonNode json = get(defaultRegion.host() + "/sc2/player/{id}", MAX_BYTES, none(), bnetAccountId);
        if (!json.isArray()) {
            throw garbled();
        }
        List<Sc2ProfileRef> refs = new ArrayList<>();
        for (JsonNode node : json) {
            Long profileId = number(node.path("profileId"));
            if (profileId == null || !node.path("regionId").isNumber() || !node.path("realmId").isNumber()) {
                throw garbled();
            }
            refs.add(new Sc2ProfileRef(node.get("regionId").asInt(), node.get("realmId").asInt(), profileId,
                    text(node.path("name"))));
        }
        return refs;
    }

    @Override
    public Sc2Profile sc2Profile(BlizzardRegion host, Sc2ProfileRef ref, String locale, boolean detail) {
        String base = host.host() + "/sc2/profile/{region}/{realm}/{profile}";
        Object[] vars = {ref.regionId(), ref.realmId(), ref.profileId(), locale};
        JsonNode json = object(get(base + "?locale={locale}", MAX_BYTES, none(), vars));
        JsonNode summary = json.path("summary");
        if (!summary.isObject() || !summary.path("displayName").isString()) {
            throw garbled();
        }
        Map<String, Sc2Profile.Mode> modes = new LinkedHashMap<>();
        JsonNode snapshot = json.path("snapshot").path("seasonSnapshot");
        for (Map.Entry<String, JsonNode> mode : snapshot.properties()) {
            JsonNode value = mode.getValue();
            modes.put(mode.getKey(), new Sc2Profile.Mode(rank(value.path("rank")), text(value.path("leagueName")),
                    value.path("totalGames").asInt(0), value.path("totalWins").asInt(0)));
        }
        List<Sc2LadderEntry> ladder = new ArrayList<>();
        if (detail) {
            JsonNode entries = object(get(base + "/ladder/summary?locale={locale}", MAX_BYTES, none(), vars))
                    .path("showCaseEntries");
            if (!entries.isArray()) {
                throw garbled();
            }
            for (JsonNode entry : entries) {
                ladder.add(new Sc2LadderEntry(text(entry.path("team").path("localizedGameMode")),
                        text(entry.path("leagueName")), text(entry.path("localizedDivisionName")),
                        rank(entry.path("rank")), entry.path("wins").asInt(0), entry.path("losses").asInt(0)));
            }
        }
        return new Sc2Profile(summary.get("displayName").asString(), text(summary.path("clanName")),
                text(summary.path("clanTag")), text(summary.path("portrait")),
                summary.path("totalSwarmLevel").asInt(0), summary.path("totalAchievementPoints").asInt(0), modes, ladder);
    }

    // --- Diablo III ---

    @Override
    public D3Profile d3Profile(BlizzardRegion region, String battleTag, String locale) {
        JsonNode json = object(get(region.host() + "/d3/profile/{tag}/?locale={locale}", MAX_BYTES, d3NotFound(),
                battleTag.replace('#', '-'), locale));
        if (!json.path("heroes").isArray()) {
            throw garbled();
        }
        List<D3Hero> heroes = new ArrayList<>();
        for (JsonNode hero : json.get("heroes")) {
            if (!hero.path("id").isNumber() || !hero.path("name").isString()) {
                throw garbled();
            }
            heroes.add(new D3Hero(hero.get("id").asLong(), hero.get("name").asString(), text(hero.path("class")),
                    hero.path("level").asInt(0), hero.path("paragonLevel").asInt(0),
                    hero.path("hardcore").asBoolean(false), hero.path("seasonal").asBoolean(false),
                    hero.path("dead").asBoolean(false),
                    hero.path("last-updated").isNumber() ? Instant.ofEpochSecond(hero.get("last-updated").asLong())
                            : null));
        }
        return new D3Profile(text(json.path("battleTag")) != null ? json.get("battleTag").asString() : battleTag,
                json.path("paragonLevel").asInt(0), json.path("paragonLevelSeason").asInt(0),
                json.path("paragonLevelHardcore").asInt(0), text(json.path("guildName")),
                json.path("kills").path("monsters").asLong(0), json.path("kills").path("elites").asLong(0),
                number(json.path("lastHeroPlayed")), heroes);
    }

    @Override
    public int d3CurrentSeason(BlizzardRegion region) {
        JsonNode json = object(get(region.host() + "/data/d3/season/", MAX_BYTES, none()));
        if (!json.path("current_season").isNumber()) {
            throw garbled();
        }
        return json.get("current_season").asInt();
    }

    // --- Hearthstone ---

    @Override
    public List<HearthstoneSet> hearthstoneSets(String locale) {
        JsonNode json = get(defaultRegion.host() + "/hearthstone/metadata/sets?locale={locale}", MAX_BYTES, none(),
                locale);
        if (!json.isArray()) {
            throw garbled();
        }
        List<HearthstoneSet> sets = new ArrayList<>();
        for (JsonNode set : json) {
            if (!set.path("id").isNumber() || !set.path("name").isString()) {
                throw garbled();
            }
            sets.add(new HearthstoneSet(set.get("id").asInt(), set.get("name").asString(), text(set.path("slug")),
                    text(set.path("type")), integer(set.path("collectibleCount"))));
        }
        return sets;
    }

    @Override
    public HearthstoneDeck hearthstoneDeck(String code, String locale) {
        JsonNode json = object(get(defaultRegion.host() + "/hearthstone/deck?code={code}&locale={locale}", MAX_BYTES,
                none(), code, locale));
        if (!json.path("deckCode").isString() || !json.path("cards").isArray()) {
            throw garbled();
        }
        List<HearthstoneDeck.Card> cards = new ArrayList<>();
        for (JsonNode card : json.get("cards")) {
            if (!card.path("id").isNumber() || !card.path("name").isString()) {
                throw garbled();
            }
            cards.add(new HearthstoneDeck.Card(card.get("id").asLong(), card.get("name").asString(),
                    integer(card.path("manaCost")), text(card.path("image"))));
        }
        return new HearthstoneDeck(json.get("deckCode").asString(), text(json.path("format")),
                text(json.path("hero").path("name")), text(json.path("class").path("name")),
                json.path("cardCount").asInt(cards.size()), cards);
    }

    // --- HTTP ---

    private static Predicate<JsonNode> none() {
        return node -> false;
    }

    private static Predicate<JsonNode> wowNotFound() {
        return node -> node.path("code").asInt(0) == 404 && "BLZWEBAPI00000404".equals(text(node.path("type")));
    }

    private static Predicate<JsonNode> d3NotFound() {
        return node -> "NOTFOUND".equals(text(node.path("code")));
    }

    /**
     * GET with the application token; a 401 renews it and retries once. Not found is thrown when {@code notFound}
     * recognises the 404 body.
     */
    private JsonNode get(String template, int maxBytes, Predicate<JsonNode> notFound, Object... vars) {
        for (int attempt = 0; ; attempt++) {
            String token = tokens.token();
            Answer answer = send(template, token, maxBytes, vars);
            if (answer.status() == 401) {
                tokens.invalidate(token);
                if (attempt == 0) {
                    continue;
                }
            }
            return interpret(answer, notFound);
        }
    }

    /** Charges the limiter, then sends; the token is only ever put in the {@code Authorization} header. */
    private Answer send(String template, String token, int maxBytes, Object... vars) {
        if (!limiter.budget().tryAcquire()) {
            throw new BlizzardUnavailableException("the Blizzard rate limit does not allow a request now");
        }
        try {
            return restClient.get().uri(template, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, "application/json").header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        String retryAfter = res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                        if (status != 404 && !res.getStatusCode().is2xxSuccessful()) {
                            return new Answer(status, retryAfter, null, false);
                        }
                        byte[] body = readLimited(res.getBody(), maxBytes);
                        return new Answer(status, retryAfter, body, body == null);
                    });
        } catch (RestClientException e) {
            throw new BlizzardUnavailableException("the Blizzard request failed");
        }
    }

    /** At most {@code max} bytes; null when there are more. */
    static byte[] readLimited(java.io.InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        return bytes.length > max ? null : bytes;
    }

    private JsonNode interpret(Answer answer, Predicate<JsonNode> notFound) {
        int status = answer.status();
        if (status == 429) {
            limiter.pause(retryAfter(answer.retryAfter()));
            throw new BlizzardUnavailableException("Blizzard rate limit hit (429)");
        }
        if (answer.tooBig()) {
            throw new BlizzardUnavailableException("the Blizzard answer is too big");
        }
        if (status == 404) {
            JsonNode body = parseOrNull(answer.body());
            if (body != null && notFound.test(body)) {
                throw new BlizzardNotFoundException("Blizzard does not know it");
            }
            throw new BlizzardUnavailableException("Blizzard answered 404 without a recognised body");
        }
        if (status < 200 || status >= 300) {
            throw new BlizzardUnavailableException("Blizzard answered " + status);
        }
        JsonNode json = parseOrNull(answer.body());
        if (json == null) {
            throw new BlizzardUnavailableException("the Blizzard answer is not JSON");
        }
        return json;
    }

    private JsonNode parseOrNull(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Duration retryAfter(String header) {
        try {
            long seconds = header == null ? 0 : Long.parseLong(header.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : PAUSE;
        } catch (NumberFormatException e) {
            return PAUSE;
        }
    }

    // --- mapping ---

    private static BlizzardUnavailableException garbled() {
        return new BlizzardUnavailableException("the Blizzard answer does not have the expected shape");
    }

    private static JsonNode object(JsonNode node) {
        if (!node.isObject()) {
            throw garbled();
        }
        return node;
    }

    /** A secondary read of a found character: a recognised not found is no data (null), anything else fails. */
    private JsonNode secondary(String template, Object... vars) {
        try {
            return object(get(template, MAX_BYTES, wowNotFound(), vars));
        } catch (BlizzardNotFoundException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static Long number(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isString()) {
            try {
                return Long.parseLong(node.asString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Unranked is -1 in the profile; it is no rank. */
    private static Integer rank(JsonNode node) {
        return node.isNumber() && node.asInt() > 0 ? node.asInt() : null;
    }
}
