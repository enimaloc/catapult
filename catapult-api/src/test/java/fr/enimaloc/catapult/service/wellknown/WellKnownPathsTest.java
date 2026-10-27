package fr.enimaloc.catapult.service.wellknown;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT;
import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class WellKnownPathsTest {

    @ParameterizedTest
    @ValueSource(strings = {"security.txt", "acme-challenge/abc123", "pki-validation/A_B-c.txt", "nodeinfo", "a/b/c"})
    void acceptsWellKnownPaths(String path) {
        assertThat(WellKnownPaths.valid(WELL_KNOWN, path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/security.txt", "security.txt/", "a//b", ".", "..", "../x", "a/../b", "a/./b",
            "sp ace", "é.txt", "a%2e%2e", "a\\b", "a?b"})
    void refusesBrokenWellKnownPaths(String path) {
        assertThat(WellKnownPaths.valid(WELL_KNOWN, path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"riot.txt", "google1234.html", "BingSiteAuth.xml", "a.b.c"})
    void acceptsRootFiles(String path) {
        assertThat(WellKnownPaths.valid(ROOT, path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"riot", ".env", "riot.", "a/b.txt", "robots.txt", "sitemap.xml", "favicon.ico", "..", ""})
    void refusesBrokenRootFiles(String path) {
        assertThat(WellKnownPaths.valid(ROOT, path)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void refusesNulls() {
        assertThat(WellKnownPaths.valid(WELL_KNOWN, null)).isFalse();
        assertThat(WellKnownPaths.valid(null, "security.txt")).isFalse();
    }
}
