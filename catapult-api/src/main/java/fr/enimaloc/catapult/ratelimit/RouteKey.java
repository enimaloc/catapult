package fr.enimaloc.catapult.ratelimit;

import org.springframework.http.HttpRequest;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One route of a platform: the method, the host and the path as written by the caller ({@code /app/{id}}), never the
 * resolved URL, so every app's page counts as the same route. Without the caller's URI template, the path's
 * identifier-looking segments become {@code {id}}.
 */
public record RouteKey(String host, String method, String path) {

    /** The request attribute {@code RestClient} keeps the URI template in. */
    static final String URI_TEMPLATE_ATTRIBUTE = "org.springframework.web.client.DefaultRestClient.uriTemplate";

    private static final Pattern ID_SEGMENT = Pattern.compile("\\d+|[A-Za-z0-9_-]{16,}|.*\\(.*\\).*");

    public static RouteKey of(HttpRequest request) {
        URI uri = request.getURI();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        Object template = request.getAttributes().get(URI_TEMPLATE_ATTRIBUTE);
        String path = template instanceof String t ? templatePath(t) : normalizedPath(uri.getRawPath());
        return new RouteKey(host, request.getMethod().name(), path);
    }

    /** The route's ID: {@code GET store.steampowered.com/app/{id}}. */
    public String id() {
        return method + " " + host + path;
    }

    /** A template's path: no scheme, host or query. */
    static String templatePath(String template) {
        String path = template;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return path.isEmpty() ? "/" : path;
    }

    /** A resolved path whose identifier-looking segments become {@code {id}}. */
    static String normalizedPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        StringBuilder out = new StringBuilder();
        for (String segment : rawPath.split("/", -1)) {
            if (segment.isEmpty()) continue;
            out.append('/').append(ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment);
        }
        if (rawPath.endsWith("/") && !out.isEmpty()) out.append('/');
        return out.isEmpty() ? "/" : out.toString();
    }
}
