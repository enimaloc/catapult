package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.common.dto.admin.WellKnownResponseDto;
import fr.enimaloc.catapult.service.WellKnownContent;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The well-known files catapult-web ships (static/.well-known), served while the admin has
 * configured none of that path: a fresh deployment still has its security.txt (RFC 9116) and
 * the disclosure policy it points to. A file the admin configures replaces its default.
 */
final class WellKnownDefaults {
    /** The shipped files, by path under /.well-known/; nothing else is ever read from the classpath. */
    private static final Map<String, WellKnownContent> FILES = Map.of(
            "security.txt", load("security.txt"),
            "security-policy.txt", load("security-policy.txt"));

    private WellKnownDefaults() {
    }

    /** The shipped default of {@code path}, null when there is none (or it isn't under /.well-known/). */
    static WellKnownContent find(WellKnownLocation location, String path) {
        return location == WellKnownLocation.WELL_KNOWN ? FILES.get(path) : null;
    }

    private static WellKnownContent load(String name) {
        try (InputStream in = new ClassPathResource("static/.well-known/" + name).getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new WellKnownContent(200, new WellKnownResponseDto(200, "text/plain; charset=utf-8", List.of(), content));
        } catch (IOException e) {
            throw new UncheckedIOException("Missing shipped well-known file " + name, e);
        }
    }
}
