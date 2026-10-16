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
    private final Map<Long, WellKnownFileDetailDto> files = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    MockWellKnownStore() {
        put(WellKnownLocation.WELL_KNOWN, "security.txt", "text/plain; charset=utf-8",
                "Contact: mailto:security@example.org\nExpires: 2027-10-16T00:00:00Z\n");
    }

    synchronized WellKnownContent serve(WellKnownLocation location, String path) {
        return find(location, path)
                .map(f -> new WellKnownContent(200, f.contentType(), f.content().getBytes(StandardCharsets.UTF_8)))
                .orElse(new WellKnownContent(404, null, null));
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
        put(r.location(), r.path(), r.contentType(), r.content());
        return 201;
    }

    synchronized int update(long id, WellKnownFileRequest r) {
        if (!files.containsKey(id)) return 404;
        if (!valid(r)) return 400;
        if (find(r.location(), r.path()).filter(f -> f.id() != id).isPresent()) return 409;
        files.put(id, new WellKnownFileDetailDto(id, r.location(), r.path(), r.status(), r.headers(), r.contentType(), r.content(), Instant.now()));
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

    private static boolean valid(WellKnownFileRequest r) {
        if (r.location() == null || r.path() == null || !r.path().matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
                || r.path().contains("..") || r.contentType() == null || r.contentType().isBlank() || r.content() == null
                || r.content().getBytes(StandardCharsets.UTF_8).length > 65_536) {
            return false;
        }
        if (r.location() == WellKnownLocation.ROOT && Set.of("robots.txt", "sitemap.xml", "favicon.ico").contains(r.path())) {
            return false;
        }
        return r.location() == WellKnownLocation.WELL_KNOWN || (!r.path().contains("/") && r.path().indexOf('.') > 0);
    }
}
