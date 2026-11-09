package fr.enimaloc.catapult.service.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which paths a well-known file may have: plain segments only (no "." / "..", nothing to escape),
 * several under /.well-known/, one dotted name at the root so that no page route (none has a dot)
 * nor a name catapult-web already serves there can be shadowed.
 */
public final class WellKnownPaths {
    public static final int MAX_CONTENT_BYTES = 65_536;

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> RESERVED_ROOT = Set.of("robots.txt", "sitemap.xml", "favicon.ico");

    private WellKnownPaths() {
    }

    public static boolean valid(WellKnownLocation location, String path) {
        if (location == null || path == null || path.isEmpty() || path.length() > 255) return false;
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) return false;
        }
        if (location == WellKnownLocation.WELL_KNOWN) return true;
        return segments.length == 1
                && path.indexOf('.') > 0 && !path.endsWith(".")
                && !RESERVED_ROOT.contains(path);
    }
}
