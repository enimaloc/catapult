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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks secrets (headers, query / form parameters, JSON fields) in a recorded exchange and truncates bodies. */
@Component
public class DebugRedactor {

    public static final String MASK = "[masqué]";
    static final int MAX_BODY_CHARS = 65_536;

    /**
     * Names (of headers, query / form parameters, JSON fields; compared ignoring case) that carry a credential, as
     * found in what catapult-api sends and receives: OAuth (Twitch, IGDB, MSA), Steam's key and OpenID signature,
     * the Xbox Live / XSTS / Minecraft sign-in chain, GitLab's {@code PRIVATE-TOKEN}, DtDD's {@code X-API-KEY}.
     */
    private static final Set<String> SECRET_NAMES = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "private-token",
            "key", "api_key", "apikey", "access_token", "refresh_token", "id_token", "client_secret",
            "token", "password", "code", "device_code", "user_code", "openid.sig",
            "rpsticket", "usertokens", "identitytoken");
    /** Any other name containing one of these is taken for a credential too, unless {@link #NOT_SECRET}. */
    private static final List<String> SECRET_NAME_PARTS = List.of(
            "token", "secret", "password", "passwd", "ticket", "signature", "apikey", "api_key", "api-key");
    /** Names that look like a credential's but only describe one. */
    private static final Set<String> NOT_SECRET = Set.of("token_type", "tokentype");
    /** Secret names too common in plain prose ({@code status code: 500}) to mask a {@code name: value} after them. */
    private static final Set<String> NOT_A_HEADER_IN_TEXT = Set.of("code", "key");

    private final JsonMapper json = JsonMapper.builder().build();

    public String headers(HttpHeaders headers, boolean reveal) {
        if (headers == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : headers.headerSet()) {
            boolean masked = !reveal && isSecretName(e.getKey());
            for (String value : e.getValue()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(e.getKey()).append(": ").append(masked ? MASK : reveal ? value : text(value, false));
            }
        }
        return sb.toString();
    }

    /** Whether a header of that name carries a secret (masked unless revealed). */
    public boolean isSecretHeader(String name) {
        return isSecretName(name);
    }

    /** Whether a header, parameter or JSON field of that name carries a credential (ignoring case). */
    static boolean isSecretName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (NOT_SECRET.contains(n)) {
            return false;
        }
        if (SECRET_NAMES.contains(n)) {
            return true;
        }
        for (String part : SECRET_NAME_PARTS) {
            if (n.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** A raw query string ({@code a=1&token=x}), its secret parameters masked unless revealed. Null stays null. */
    public String query(String query, boolean reveal) {
        return query == null || reveal ? query : maskParams(query);
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
        if (!reveal && type == null) {
            text = maskText(text);
        } else if (!reveal) {
            if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(type)) {
                text = maskParams(text);
            } else if (isJson(type)) {
                text = maskJson(text);
            }
        }
        return truncate(text);
    }

    /**
     * Free text (an exception message, a header value, say) that may quote a URL, a form, a JSON fragment or a
     * header: secret {@code name=value} parameters, JSON fields, {@code Name: value} headers and
     * {@code Bearer <token>} / {@code Basic <credentials>} / {@code XBL3.0 x=<hash>;<token>} values are masked, then
     * the text is truncated. Null stays null.
     */
    public String text(String text, boolean reveal) {
        if (text == null) {
            return null;
        }
        return truncate(reveal ? text : maskText(text));
    }

    private static String maskText(String text) {
        String masked = maskHeadersInText(text);
        masked = CREDENTIAL_SCHEME.matcher(masked).replaceAll(r -> Matcher.quoteReplacement(r.group(1) + " " + MASK));
        masked = FREE_TEXT_PARAM.matcher(masked).replaceAll(r -> isSecretName(r.group(1))
                ? Matcher.quoteReplacement(r.group(1) + "=" + MASK)
                : Matcher.quoteReplacement(r.group()));
        return maskJsonByRegex(masked);
    }

    /** A parameter name not glued to a longer name ({@code monkey=} is not {@code key=}), its value up to a separator. */
    private static final Pattern FREE_TEXT_PARAM = Pattern.compile(
            "(?<![\\w.-])([A-Za-z_][\\w.-]*)=[^&\\s\"'#<>,;)\\]}]*");
    /** {@code Name:} as a header is written; what follows, up to the end of the line or a quote, is its value. */
    private static final Pattern HEADER_IN_TEXT = Pattern.compile("(?<![\\w.-])([A-Za-z][\\w-]*)[ \\t]*:[ \\t]*");
    /** An HTTP credential written with its scheme: {@code Bearer <token>}, {@code Basic <b64>}, {@code XBL3.0 x=…}. */
    private static final Pattern CREDENTIAL_SCHEME = Pattern.compile(
            "(?i)\\b(Bearer|Basic|XBL3\\.0)[ \\t]+(?!\\[masqué])[^\\s\"',]+");

    private static String maskHeadersInText(String text) {
        Matcher m = HEADER_IN_TEXT.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && m.find(from)) {
            String name = m.group(1);
            if (!isSecretName(name) || NOT_A_HEADER_IN_TEXT.contains(name.toLowerCase(Locale.ROOT))) {
                from = m.end(1);
                continue;
            }
            int end = m.end();
            while (end < text.length() && "\r\n\"".indexOf(text.charAt(end)) < 0) {
                end++;
            }
            if (end == m.end()) {
                from = end;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, copied, m.end()).append(MASK);
            copied = end;
            from = end;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    private static Charset charset(MediaType type) {
        try {
            Charset cs = type == null ? null : type.getCharset();
            return cs != null ? cs : StandardCharsets.UTF_8;
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** A JSON field, its value a string, an array of scalars, or a bare scalar. */
    private static final Pattern JSON_FIELD = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^\\[\\]{}]*\\]|[^,}\\]\\s\\[{\"]+)");

    private static String maskJsonByRegex(String text) {
        return JSON_FIELD.matcher(text).replaceAll(r -> isSecretName(r.group(1))
                ? Matcher.quoteReplacement("\"" + r.group(1) + "\":\"" + MASK + "\"")
                : Matcher.quoteReplacement(r.group()));
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
            if (isSecretName(decode(name))) {
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
                if (isSecretName(name)) {
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
