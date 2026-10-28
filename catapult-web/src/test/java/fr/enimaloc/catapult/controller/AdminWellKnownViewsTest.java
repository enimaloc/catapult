package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT;
import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class AdminWellKnownViewsTest {
    static WellKnownFileDto file(String path, fr.enimaloc.catapult.common.dto.admin.WellKnownLocation l) {
        return new WellKnownFileDto(1, l, path, 200, "text/plain", 1, Instant.EPOCH);
    }

    @Test
    void urls() {
        assertThat(AdminWellKnownViews.url(WELL_KNOWN, "acme-challenge/a")).isEqualTo("/.well-known/acme-challenge/a");
        assertThat(AdminWellKnownViews.url(ROOT, "riot.txt")).isEqualTo("/riot.txt");
    }

    @Test
    void configuredSuffixes_countFilesUnderADirectorySuffix_butNotRootFiles() {
        var configured = AdminWellKnownViews.configured(List.of(
                file("security.txt", WELL_KNOWN), file("acme-challenge/abc", WELL_KNOWN), file("assetlinks.json", ROOT)));
        assertThat(configured).containsExactlyInAnyOrder("security.txt", "acme-challenge");
    }

    @Test
    void contentTypeFromTheExtension() {
        assertThat(AdminWellKnownViews.contentType("assetlinks.json")).isEqualTo("application/json");
        assertThat(AdminWellKnownViews.contentType("a/b.XML")).isEqualTo("application/xml");
        assertThat(AdminWellKnownViews.contentType("google1.html")).isEqualTo("text/html; charset=utf-8");
        assertThat(AdminWellKnownViews.contentType("acme-challenge/abc")).isEqualTo("text/plain; charset=utf-8");
        assertThat(AdminWellKnownViews.contentType(null)).isEqualTo("text/plain; charset=utf-8");
    }
}
