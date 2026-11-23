package fr.enimaloc.catapult.service.supercell;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What can be read of a Supercell API key without asking Supercell: its shape (a JWT, three base64url parts, the
 * first starting {@code eyJ}) and, when its payload is readable, the scopes (the game it opens) and the IP ranges it
 * is bound to. Nothing here checks the signature: Supercell alone accepts or refuses the key.
 */
public final class SupercellKeys {

    /** A JWT as Supercell's developer portals hand them out; at most 4 kB. */
    public static final Pattern FORMAT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    static final int MAX_LENGTH = 4096;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** What the payload says: the scopes ({@code clash}, {@code royale}, {@code brawlstars}) and the IP ranges. */
    public record Claims(List<String> scopes, List<String> cidrs) {
    }

    private SupercellKeys() {
    }

    /** Whether {@code key} has the shape of a Supercell key. */
    public static boolean wellFormed(String key) {
        return key != null && key.length() <= MAX_LENGTH && FORMAT.matcher(key).matches();
    }

    /** The claims of a well-formed key's payload; empty when it cannot be read. */
    public static Optional<Claims> claims(String key) {
        if (!wellFormed(key)) {
            return Optional.empty();
        }
        String payload = key.substring(key.indexOf('.') + 1, key.lastIndexOf('.'));
        JsonNode json;
        try {
            json = MAPPER.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JacksonException e) {
            return Optional.empty();
        }
        if (json == null || !json.isObject()) {
            return Optional.empty();
        }
        List<String> scopes = new ArrayList<>();
        for (JsonNode scope : json.path("scopes")) {
            if (scope.isString()) {
                scopes.add(scope.asString());
            }
        }
        List<String> cidrs = new ArrayList<>();
        for (JsonNode limit : json.path("limits")) {
            for (JsonNode cidr : limit.path("cidrs")) {
                if (cidr.isString()) {
                    cidrs.add(cidr.asString());
                }
            }
        }
        return Optional.of(new Claims(List.copyOf(scopes), List.copyOf(cidrs)));
    }

    /** The first and last 4 characters only. */
    public static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }
}
