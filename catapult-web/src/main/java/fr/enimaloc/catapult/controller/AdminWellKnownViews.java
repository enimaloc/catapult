package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** What the Well-known tab shows of a file: its public URL, the registry suffixes in use, a type for a path. */
final class AdminWellKnownViews {
    private AdminWellKnownViews() {
    }

    static String url(WellKnownLocation location, String path) {
        return (location == WellKnownLocation.ROOT ? "/" : "/.well-known/") + path;
    }

    /** The /.well-known/ paths in use and their first segment (acme-challenge for acme-challenge/abc). */
    static Set<String> configured(List<WellKnownFileDto> files) {
        return files.stream()
                .filter(f -> f.location() == WellKnownLocation.WELL_KNOWN)
                .map(f -> f.path().split("/", 2)[0])
                .collect(Collectors.toSet());
    }

    /** The type a new file at {@code path} starts with; admin-well-known.js derives the same while typing. */
    static String contentType(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".xml")) return "application/xml";
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        return "text/plain; charset=utf-8";
    }
}
