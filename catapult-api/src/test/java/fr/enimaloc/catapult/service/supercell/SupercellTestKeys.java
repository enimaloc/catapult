package fr.enimaloc.catapult.service.supercell;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Fake Supercell keys for the tests, put together at run time: each has the shape of a Supercell key (a JWT whose
 * payload says the scopes and IP ranges) but a signature no server would accept, and none is written out in a source.
 */
public final class SupercellTestKeys {

    private SupercellTestKeys() {
    }

    private static String part(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A fake key of {@code scope} ({@code clash}, {@code royale}, {@code brawlstars}) bound to {@code cidr}. */
    public static String key(String scope, String cidr, String salt) {
        String header = part("{\"typ\":\"JWT\",\"alg\":\"none\",\"kid\":\"fake-" + salt + "\"}");
        String payload = part("{\"iss\":\"fake\",\"scopes\":[\"" + scope + "\"],\"limits\":[{\"type\":\"client\","
                + "\"cidrs\":[\"" + cidr + "\"]}]}");
        return header + "." + payload + ".fake-signature-" + salt;
    }

    /** A fake Clash of Clans key. */
    public static String clash(String salt) {
        return key("clash", "203.0.113.7/32", salt);
    }

    /** A fake key whose payload is not JSON. */
    public static String unreadable() {
        return part("{\"typ\":\"JWT\"}") + "." + "eyJ" + "notjsonatall" + ".fake-signature";
    }
}
