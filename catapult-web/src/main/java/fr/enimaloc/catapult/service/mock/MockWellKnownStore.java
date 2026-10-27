package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.*;
import fr.enimaloc.catapult.service.WellKnownContent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The mock's well-known files: one store for the whole application, not per mock session, since the
 * files are fetched without a session (a curl, a crawler). Path rules are only roughly mirrored
 * (a root name needs a dot); catapult-api's are the real ones.
 */
class MockWellKnownStore {
    private static final Set<String> REFUSED_HEADERS = Set.of("content-length", "transfer-encoding", "connection",
            "content-type", "set-cookie", "x-content-type-options");
    private final Map<Long, WellKnownFileDetailDto> files = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    MockWellKnownStore() {
        put(WellKnownLocation.WELL_KNOWN, "security.txt", "text/plain; charset=utf-8",
                "Contact: mailto:security@example.org\nExpires: 2027-10-16T00:00:00Z\n");
        files.put(ids.incrementAndGet(), new WellKnownFileDetailDto(ids.get(), WellKnownLocation.WELL_KNOWN, "change-password", 302,
                List.of(new WellKnownHeader("Location", "/settings")), "text/plain", "", Instant.now()));
    }

    /** A small flat schema for {@code gpc.json}, standing in for SchemaStore's; null for any other path. */
    String schema(String path) {
        return "gpc.json".equals(path)
                ? "{\"type\":\"object\",\"properties\":{\"gpc\":{\"type\":\"boolean\",\"title\":\"GPC\"},"
                + "\"lastUpdate\":{\"type\":\"string\",\"description\":\"ISO date\"}},\"required\":[\"gpc\"]}"
                : null;
    }

    synchronized WellKnownContent serve(WellKnownLocation location, String path) {
        return find(location, path)
                .map(f -> new WellKnownContent(200, new WellKnownResponseDto(f.status(), f.contentType(), f.headers(), f.content())))
                .orElse(new WellKnownContent(404, null));
    }

    synchronized List<WellKnownFileDto> list() {
        return files.values().stream().map(f -> new WellKnownFileDto(f.id(), f.location(), f.path(), f.status(), f.contentType(),
                f.content().getBytes(StandardCharsets.UTF_8).length, f.updatedAt())).toList();
    }

    synchronized WellKnownFileDetailDto get(long id) {
        return files.get(id);
    }

    synchronized int create(WellKnownFileRequest r) {
        if (!valid(r)) return 400;
        if (find(r.location(), r.path()).isPresent()) return 409;
        long id = ids.incrementAndGet();
        files.put(id, detail(id, r));
        return 201;
    }

    synchronized int update(long id, WellKnownFileRequest r) {
        if (!files.containsKey(id)) return 404;
        if (!valid(r)) return 400;
        if (find(r.location(), r.path()).filter(f -> f.id() != id).isPresent()) return 409;
        files.put(id, detail(id, r));
        return 200;
    }

    synchronized int delete(long id) {
        return files.remove(id) == null ? 404 : 204;
    }

    IanaRegistryDto registry() {
        return new IanaRegistryDto(List.of(
                new IanaWellKnownDto("acme-challenge", "RFC 8555, Section 8.3", "permanent", "IETF"),
                new IanaWellKnownDto("assetlinks.json", "https://github.com/google/digitalassetlinks", "permanent", "Google_Inc."),
                new IanaWellKnownDto("change-password", "https://w3c.github.io/webappsec-change-password-url/", "provisional", "W3C"),
                new IanaWellKnownDto("security.txt", "RFC 9116", "permanent", "IETF")), "SNAPSHOT");
    }

    private Optional<WellKnownFileDetailDto> find(WellKnownLocation location, String path) {
        return files.values().stream().filter(f -> f.location() == location && f.path().equals(path)).findFirst();
    }

    private void put(WellKnownLocation location, String path, String type, String content) {
        long id = ids.incrementAndGet();
        files.put(id, new WellKnownFileDetailDto(id, location, path, 200, java.util.List.of(), type, content, Instant.now()));
    }

    /** The stored file for a request, with the API's defaults: no status is 200, no headers is none. */
    private static WellKnownFileDetailDto detail(long id, WellKnownFileRequest r) {
        int status = r.status() == null || r.status() == 0 ? 200 : r.status();
        List<WellKnownHeader> headers = r.headers() == null ? List.of() : r.headers();
        return new WellKnownFileDetailDto(id, r.location(), r.path(), status, headers, r.contentType().isBlank() ? "text/plain; charset=utf-8" : r.contentType(), r.content(), Instant.now());
    }

    /** A subset of catapult-api's rules: a 200-599 status, no refused header, a Location for a 3xx. */
    private static boolean validResponse(WellKnownFileRequest r) {
        int status = r.status() == null || r.status() == 0 ? 200 : r.status();
        if (status < 200 || status > 599) return false;
        List<WellKnownHeader> headers = r.headers() == null ? List.of() : r.headers();
        for (WellKnownHeader h : headers) {
            if (h.name() == null || h.value() == null || h.value().isBlank() || REFUSED_HEADERS.contains(h.name().toLowerCase(Locale.ROOT))) return false;
        }
        return !Set.of(301, 302, 303, 307, 308).contains(status) || headers.stream().anyMatch(h -> h.name().equalsIgnoreCase("Location") && !h.value().isBlank());
    }

    private static boolean valid(WellKnownFileRequest r) {
        if (!validResponse(r)) return false;
        if (r.location() == null || r.path() == null || !r.path().matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
                || r.path().contains("..") || r.contentType() == null || (r.contentType().isBlank() && r.content() != null && !r.content().isEmpty()) || r.content() == null
                || r.content().getBytes(StandardCharsets.UTF_8).length > 65_536) {
            return false;
        }
        if (r.location() == WellKnownLocation.ROOT && Set.of("robots.txt", "sitemap.xml", "favicon.ico").contains(r.path())) {
            return false;
        }
        return r.location() == WellKnownLocation.WELL_KNOWN || (!r.path().contains("/") && r.path().indexOf('.') > 0);
    }
}
