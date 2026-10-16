package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.service.WellKnownContent;
import org.junit.jupiter.api.Test;

import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT;
import static fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN;
import static org.assertj.core.api.Assertions.assertThat;

class MockWellKnownStoreTest {
    private final MockWellKnownStore store = new MockWellKnownStore();

    private static WellKnownFileRequest req(fr.enimaloc.catapult.common.dto.admin.WellKnownLocation l, String path) {
        return new WellKnownFileRequest(l, path, "text/plain", "body");
    }

    @Test
    void servesTheSeededSecurityTxt() {
        WellKnownContent c = store.serve(WELL_KNOWN, "security.txt");
        assertThat(c.status()).isEqualTo(200);
        assertThat(c.contentType()).isEqualTo("text/plain; charset=utf-8");
    }

    @Test
    void createsThenServes() {
        assertThat(store.create(req(WELL_KNOWN, "acme-challenge/abc"))).isEqualTo(201);
        assertThat(new String(store.serve(WELL_KNOWN, "acme-challenge/abc").body())).isEqualTo("body");
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
}
