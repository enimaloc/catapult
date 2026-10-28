package fr.enimaloc.catapult.service.mock;

import java.util.List;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.service.WellKnownContent;
import org.junit.jupiter.api.Test;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT;
import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class MockWellKnownStoreTest {
    private final MockWellKnownStore store = new MockWellKnownStore();

    private static WellKnownFileRequest req(fr.enimaloc.catapult.common.dto.admin.WellKnownLocation l, String path) {
        return new WellKnownFileRequest(l, path, 200, List.of(), "text/plain", "body");
    }

    @Test
    void servesTheSeededSecurityTxt() {
        WellKnownContent c = store.serve(WELL_KNOWN, "security.txt");
        assertThat(c.status()).isEqualTo(200);
        assertThat(c.response().contentType()).isEqualTo("text/plain; charset=utf-8");
    }

    @Test
    void createsThenServes() {
        assertThat(store.create(req(WELL_KNOWN, "acme-challenge/abc"))).isEqualTo(201);
        assertThat(store.serve(WELL_KNOWN, "acme-challenge/abc").response().content()).isEqualTo("body");
        assertThat(store.serve(WELL_KNOWN, "other").status()).isEqualTo(404);
    }

    @Test
    void refusesATakenPathAndARootWithoutADot() {
        assertThat(store.create(req(WELL_KNOWN, "security.txt"))).isEqualTo(409);
        assertThat(store.create(req(ROOT, "nodot"))).isEqualTo(400);
    }

    @Test
    void updateToAnotherFilesPathIsAConflict() {
        store.create(req(WELL_KNOWN, "a.txt"));
        long id = store.list().stream().filter(f -> f.path().equals("a.txt")).findFirst().orElseThrow().id();
        assertThat(store.update(id, req(WELL_KNOWN, "security.txt"))).isEqualTo(409);
        assertThat(store.update(id, req(WELL_KNOWN, "a.txt"))).isEqualTo(200);
        assertThat(store.update(999, req(WELL_KNOWN, "z.txt"))).isEqualTo(404);
    }

    @Test
    void deleteThenGone() {
        long id = store.list().get(0).id();
        assertThat(store.delete(id)).isEqualTo(204);
        assertThat(store.delete(id)).isEqualTo(404);
    }

    @Test
    void refusesReservedRootNamesAndOversizedContent() {
        for (String name : new String[]{"robots.txt", "sitemap.xml", "favicon.ico"}) {
            assertThat(store.create(req(ROOT, name))).isEqualTo(400);
        }
        assertThat(store.create(new WellKnownFileRequest(ROOT, "big.txt", 200, List.of(), "text/plain", "x".repeat(65_537)))).isEqualTo(400);
        assertThat(store.create(new WellKnownFileRequest(ROOT, "ok.txt", 200, List.of(), "text/plain", "x".repeat(65_536)))).isEqualTo(201);
    }

    @Test
    void createAndUpdate_applyTheApiDefaults_whenStatusAndHeadersAreAbsent() {
        var bare = new WellKnownFileRequest(WELL_KNOWN, "a.txt", null, null, "text/plain", "x");
        assertThat(store.create(bare)).isEqualTo(201);
        var created = store.list().stream().filter(f -> f.path().equals("a.txt")).findFirst().orElseThrow();
        assertThat(created.status()).isEqualTo(200);
        assertThat(store.get(created.id()).headers()).isEmpty();

        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "b.txt", 302,
                List.of(new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Location", "/x")), "text/plain", ""))).isEqualTo(201);
        var redirect = store.list().stream().filter(f -> f.path().equals("b.txt")).findFirst().orElseThrow();
        assertThat(redirect.status()).isEqualTo(302);
        assertThat(store.get(redirect.id()).headers()).containsExactly(
                new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Location", "/x"));

        assertThat(store.update(redirect.id(), new WellKnownFileRequest(WELL_KNOWN, "b.txt", null, null, "text/plain", "y"))).isEqualTo(200);
        assertThat(store.get(redirect.id()).status()).isEqualTo(200);
        assertThat(store.get(redirect.id()).headers()).isEmpty();
    }

    @Test
    void refusesABlankHeaderValue() {
        var blank = List.of(new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Content-Security-Policy", " "));
        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "a.txt", 200, blank, "text/plain", "x"))).isEqualTo(400);
    }

    @Test
    void aBlankContentTypeIsAcceptedWithoutContent_andDefaulted() {
        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "c.txt", 200, List.of(), " ", "x"))).isEqualTo(400);
        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "c.txt", 200, List.of(), " ", ""))).isEqualTo(201);
        var file = store.list().stream().filter(f -> f.path().equals("c.txt")).findFirst().orElseThrow();
        assertThat(store.get(file.id()).contentType()).isEqualTo("text/plain; charset=utf-8");
    }

    @Test
    void servesTheSeededRedirect() {
        WellKnownContent c = store.serve(WELL_KNOWN, "change-password");
        assertThat(c.status()).isEqualTo(200);
        assertThat(c.response().status()).isEqualTo(302);
        assertThat(c.response().headers()).containsExactly(
                new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Location", "/settings"));
    }

    @Test
    void refusesARedirectWithoutLocation_andARefusedHeader_andABadStatus() {
        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "r.txt", 302, List.of(), "text/plain", ""))).isEqualTo(400);
        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "c.txt", 200, List.of(
                new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Set-Cookie", "a=b")), "text/plain", ""))).isEqualTo(400);
        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "s.txt", 99, List.of(), "text/plain", ""))).isEqualTo(400);
    }

    @Test
    void servesTheStoredStatusAndHeaders() {
        store.create(new WellKnownFileRequest(WELL_KNOWN, "g.txt", 410, List.of(
                new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Cache-Control", "no-store")), "text/plain", "gone"));
        var r = store.serve(WELL_KNOWN, "g.txt").response();
        assertThat(r.status()).isEqualTo(410);
        assertThat(r.headers()).hasSize(1);
    }

    @Test
    void schema_existsForGpcJsonOnly() {
        assertThat(store.schema("gpc.json")).contains("\"gpc\"");
        assertThat(store.schema("x")).isNull();
    }

    @Test
    void a304WithoutLocationIsAccepted() {
        assertThat(store.create(new WellKnownFileRequest(WELL_KNOWN, "nm.txt", 304, List.of(), "text/plain", ""))).isEqualTo(201);
    }
}
