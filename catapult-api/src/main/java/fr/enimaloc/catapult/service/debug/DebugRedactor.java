package fr.enimaloc.catapult.service.debug;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Masks secrets (headers, query / form parameters, JSON fields) in a recorded exchange and truncates bodies. */
@Component
public class DebugRedactor {

    static final String MASK = "[masqué]";
    static final int MAX_BODY_CHARS = 65_536;

    private static final Set<String> MASKED_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization");
    private static final Set<String> MASKED_PARAMS = Set.of(
            "key", "api_key", "apikey", "access_token", "refresh_token", "id_token", "client_secret",
            "token", "password", "code", "device_code", "openid.sig");

    private final JsonMapper json = JsonMapper.builder().build();

    public String headers(HttpHeaders headers, boolean reveal) {
        if (headers == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.headerSet()) {
            boolean masked = !reveal && MASKED_HEADERS.contains(e.getKey().toLowerCase());
            for (String value : e.getValue()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(e.getKey()).append(": ").append(masked ? MASK : value);
            }
        }
        return sb.toString();
    }

    public String url(URI uri, boolean reveal) {
        if (uri == null) {
            return "";
        }
        String s = uri.toString();
        if (reveal) {
            return s;
        }
        int q = s.indexOf('?');
        if (q < 0) {
            return s;
        }
        int hash = s.indexOf('#', q);
        String query = hash < 0 ? s.substring(q + 1) : s.substring(q + 1, hash);
        String fragment = hash < 0 ? "" : s.substring(hash);
        return s.substring(0, q + 1) + maskParams(query) + fragment;
    }

    public String body(byte[] bytes, MediaType type, boolean reveal) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        Charset cs = charset(type);
        String text = new String(bytes, cs);
        if (!reveal && type != null) {
            if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(type)) {
                text = maskParams(text);
            } else if (isJson(type)) {
                text = maskJson(text);
            }
        }
        return truncate(text);
    }

    private static Charset charset(MediaType type) {
        try {
            Charset cs = type == null ? null : type.getCharset();
            return cs != null ? cs : StandardCharsets.UTF_8;
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final Pattern JSON_SECRET_FIELD = Pattern.compile(
            "\"(" + String.join("|", MASKED_PARAMS.stream().map(Pattern::quote).toList()) + ")\"\\s*:\\s*"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private static String maskJsonByRegex(String text) {
        return JSON_SECRET_FIELD.matcher(text).replaceAll(r -> "\"" + r.group(1) + "\":\"" + MASK + "\"");
    }

    private static boolean isJson(MediaType type) {
        return MediaType.APPLICATION_JSON.isCompatibleWith(type)
                || "json".equalsIgnoreCase(type.getSubtype())
                || type.getSubtype().toLowerCase().endsWith("+json");
    }

    private static String maskParams(String query) {
        String[] parts = query.split("&", -1);
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = parts[i].substring(0, eq);
            if (MASKED_PARAMS.contains(decode(name).toLowerCase())) {
                parts[i] = name + "=" + MASK;
            }
        }
        return String.join("&", parts);
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private String maskJson(String text) {
        try {
            JsonNode root = json.readTree(text);
            if (root == null || root.isMissingNode()) {
                return maskJsonByRegex(text);
            }
            maskNode(root);
            return json.writeValueAsString(root);
        } catch (RuntimeException e) {
            return maskJsonByRegex(text);
        }
    }

    private void maskNode(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (String name : List.copyOf(obj.propertyNames())) {
                if (MASKED_PARAMS.contains(name.toLowerCase())) {
                    obj.put(name, MASK);
                } else {
                    maskNode(obj.get(name));
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::maskNode);
        }
    }

    static String truncate(String text) {
        if (text.length() <= MAX_BODY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_BODY_CHARS) + "…(+" + (text.length() - MAX_BODY_CHARS) + " chars)";
    }
}
