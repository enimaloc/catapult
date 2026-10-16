package fr.enimaloc.catapult.service.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownHeader;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The status and headers a well-known file may be served with: what catapult-web computes itself
 * (length, encoding, type, nosniff) or must never send from a public file (cookies) is refused, and a
 * redirect needs a Location that can't send the visitor to another scheme or a protocol-relative host.
 */
public final class WellKnownHeaders {
    static final int MAX_HEADERS = 20;
    static final int MAX_VALUE = 1024;

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> REFUSED = Set.of("content-length", "transfer-encoding", "connection",
            "content-type", "set-cookie", "x-content-type-options");
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    /** http(s) with a host (not empty, no whitespace). */
    private static final Pattern ABSOLUTE = Pattern.compile("https?://[^/\\s?#]+.*");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<WellKnownHeader>> LIST = new TypeReference<>() {};

    private WellKnownHeaders() {
    }

    public static void validate(int status, List<WellKnownHeader> headers) {
        if (status < 200 || status > 599) throw refused("Status out of 200-599");
        if (headers.size() > MAX_HEADERS) throw refused("Too many headers");
        Set<String> seen = new HashSet<>();
        String location = null;
        for (WellKnownHeader header : headers) {
            if (header == null) throw refused("Invalid header");
            String name = header.name();
            String value = header.value();
            if (name == null || !TOKEN.matcher(name).matches()) throw refused("Invalid header name");
            String key = name.toLowerCase(Locale.ROOT);
            if (REFUSED.contains(key)) throw refused("Header not allowed: " + name);
            if (!seen.add(key)) throw refused("Duplicate header: " + name);
            if (value == null || value.length() > MAX_VALUE || !fieldValue(value)) {
                throw refused("Invalid header value: " + name);
            }
            if (key.equals("location")) location = value;
        }
        if (REDIRECTS.contains(status) && !safeLocation(location)) throw refused("A redirect needs a valid Location");
    }

    /** RFC 9110 field-value characters: VCHAR, SP, HTAB and obs-text (0x80-0xFF). */
    private static boolean fieldValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = c == ' ' || c == '\t' || (c >= 0x21 && c <= 0x7E) || (c >= 0x80 && c <= 0xFF);
            if (!ok) return false;
        }
        return true;
    }

    private static boolean safeLocation(String location) {
        if (location == null) return false;
        // Browsers read a backslash as a slash and drop tabs and newlines, so neither may hide a "//".
        for (int i = 0; i < location.length(); i++) {
            char c = location.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7F) return false;
        }
        if (ABSOLUTE.matcher(location).matches()) return true;
        return location.startsWith("/") && !location.startsWith("//");
    }

    public static String toJson(List<WellKnownHeader> headers) {
        return JSON.writeValueAsString(headers);
    }

    public static List<WellKnownHeader> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        return List.copyOf(JSON.readValue(json, LIST));
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
