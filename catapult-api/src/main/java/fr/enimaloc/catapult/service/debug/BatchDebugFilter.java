package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Cuts one member's share out of a grouped call: the request keeps only that member's identifier and the response
 * only that member's entries. Every method returns {@code null} when no safe share can be made (the member is not
 * part of this exchange, or its response cannot be parsed): the caller then records nothing for that member, never
 * the whole batch.
 */
public final class BatchDebugFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BatchDebugFilter() {
    }

    /** {@code GetPlayerSummaries}: query {@code steamids} cut down to {@code steamId}, {@code response.players} too. */
    public static RawExchange steam(RawExchange raw, String steamId) {
        return share(raw, steamId, query -> rewriteCommaList(query, "steamids", steamId),
                root -> keepIn(child(root, "response"), "players", p -> steamId.equals(text(p, "steamid"))));
    }

    /** Helix {@code users?id=…&id=…}: only this member's {@code id=} is kept, and its entry in {@code data}. */
    public static RawExchange twitchUsers(RawExchange raw, String twitchId) {
        return share(raw, twitchId, query -> rewriteRepeated(query, "id", twitchId),
                root -> keepIn(root, "data", u -> twitchId.equals(text(u, "id"))));
    }

    /** Minecraft {@code /presence}: the {@code presence} list cut down to this (normalized) profile id. */
    public static RawExchange minecraftPresence(RawExchange raw, String profileId) {
        String wanted = MinecraftService.normalizeProfileId(profileId);
        return share(raw, profileId, query -> query,
                root -> keepIn(root, "presence", sameProfile(wanted)));
    }

    /** Minecraft {@code /friends}: friends and incoming / outgoing requests cut down to this profile id. */
    public static RawExchange minecraftFriends(RawExchange raw, String profileId) {
        String wanted = MinecraftService.normalizeProfileId(profileId);
        return share(raw, profileId, query -> query, root -> {
            if (!(root instanceof ObjectNode obj)) {
                return false;
            }
            boolean empty = true;
            for (String list : List.of("friends", "incomingRequests", "outgoingRequests")) {
                if (!keepIn(obj, list, sameProfile(wanted))) {
                    return false;
                }
                JsonNode kept = obj.get(list);
                empty &= kept == null || kept.isEmpty();
            }
            if (obj.has("empty")) {
                obj.put("empty", empty);
            }
            return true;
        });
    }

    // -------------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface QueryRewrite {
        /** The query with only the member's identifier, or null when the member is not part of it. */
        String apply(String rawQuery);
    }

    @FunctionalInterface
    private interface BodyFilter {
        /** Filters {@code root} in place; false when its shape is not the expected one. */
        boolean apply(JsonNode root);
    }

    private static RawExchange share(RawExchange raw, String key, QueryRewrite query, BodyFilter body) {
        if (raw == null || key == null || key.isEmpty()) {
            return null;
        }
        try {
            URI uri = raw.uri();
            URI filteredUri = uri;
            if (uri != null) {
                String rawQuery = uri.getRawQuery();
                String newQuery = rawQuery == null ? null : query.apply(rawQuery);
                if (rawQuery != null && newQuery == null) {
                    return null;
                }
                if (!Objects.equals(rawQuery, newQuery)) {
                    filteredUri = withQuery(uri, newQuery);
                }
            }
            byte[] responseBody = raw.responseBody();
            if (responseBody != null && responseBody.length > 0) {
                JsonNode root = JSON.readTree(responseBody);
                if (root == null || root.isMissingNode() || !body.apply(root)) {
                    return null;
                }
                responseBody = JSON.writeValueAsBytes(root);
            }
            String error = raw.error();
            if (error != null && uri != null && filteredUri != uri) {
                error = error.replace(uri.toString(), filteredUri.toString());
            }
            return new RawExchange(raw.at(), raw.method(), filteredUri, raw.requestHeaders(), raw.requestBody(),
                    raw.status(), withoutLength(raw.responseHeaders()), responseBody, error, raw.durationMs());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static URI withQuery(URI uri, String rawQuery) {
        String s = uri.toString();
        int q = s.indexOf('?');
        int hash = s.indexOf('#', q < 0 ? 0 : q);
        String base = q < 0 ? (hash < 0 ? s : s.substring(0, hash)) : s.substring(0, q);
        String fragment = hash < 0 ? "" : s.substring(hash);
        return URI.create(base + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery) + fragment);
    }

    /** {@code name=a,b,c} becomes {@code name=key}; null when {@code key} is not among the values. */
    private static String rewriteCommaList(String rawQuery, String name, String key) {
        List<String> parts = new ArrayList<>(Arrays.asList(rawQuery.split("&", -1)));
        boolean found = false;
        for (int i = 0; i < parts.size(); i++) {
            String[] nv = parts.get(i).split("=", 2);
            if (nv.length == 2 && name.equals(decode(nv[0]))) {
                if (Arrays.stream(decode(nv[1]).split(",")).map(String::trim).noneMatch(key::equals)) {
                    return null;
                }
                parts.set(i, nv[0] + "=" + encode(key));
                found = true;
            }
        }
        return found ? String.join("&", parts) : null;
    }

    /** Repeated {@code name=…} parameters: only {@code name=key} stays; null when it is not there. */
    private static String rewriteRepeated(String rawQuery, String name, String key) {
        List<String> kept = new ArrayList<>();
        boolean found = false;
        for (String part : rawQuery.split("&", -1)) {
            String[] nv = part.split("=", 2);
            if (nv.length == 2 && name.equals(decode(nv[0]))) {
                if (!found && key.equals(decode(nv[1]))) {
                    kept.add(part);
                    found = true;
                }
            } else {
                kept.add(part);
            }
        }
        return found ? String.join("&", kept) : null;
    }

    /**
     * Keeps, in {@code parent}'s array {@code field}, only the elements matching {@code keep}. A missing field is
     * fine (nothing to cut); a field that is not an array, or a parent that is not an object, is not.
     */
    private static boolean keepIn(JsonNode parent, String field, Predicate<JsonNode> keep) {
        if (!(parent instanceof ObjectNode obj)) {
            return false;
        }
        JsonNode list = obj.get(field);
        if (list == null || list.isNull()) {
            return true;
        }
        if (!(list instanceof ArrayNode array)) {
            return false;
        }
        ArrayNode kept = JSON.createArrayNode();
        for (JsonNode element : array) {
            if (keep.test(element)) {
                kept.add(element);
            }
        }
        obj.set(field, kept);
        return true;
    }

    private static JsonNode child(JsonNode node, String field) {
        return node instanceof ObjectNode obj ? obj.get(field) : null;
    }

    private static Predicate<JsonNode> sameProfile(String normalizedId) {
        return e -> normalizedId != null
                && normalizedId.equals(MinecraftService.normalizeProfileId(text(e, "profileId")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.isContainer() ? null : value.asString();
    }

    private static HttpHeaders withoutLength(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        HttpHeaders copy = HttpHeaders.copyOf(headers);
        copy.remove(HttpHeaders.CONTENT_LENGTH);
        return copy;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
