package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockKeysStoreTest {
    private static final String RAW = "ABCDEF0123456789ABCDEF0123456789";
    private final MockKeysStore store = new MockKeysStore();

    @Test
    void add_listsAKeyOnce_masked_andNeverRaw() {
        int before = store.page(ApiKeyProvider.STEAM).keys().size();

        assertThat(store.add(ApiKeyProvider.STEAM, " " + RAW + " ")).isEqualTo(204);
        assertThat(store.add(ApiKeyProvider.STEAM, RAW)).isEqualTo(204);

        var keys = store.page(ApiKeyProvider.STEAM).keys();
        assertThat(keys).hasSize(before + 1);
        assertThat(keys).anyMatch(k -> k.masked().equals("ABCD…6789"));
        assertThat(keys.toString()).doesNotContain(RAW);
    }

    @Test
    void add_refusesAnInvalidFormat_perProvider() {
        assertThat(store.add(ApiKeyProvider.STEAM, "ddd_abcdefghijklmnop1234")).isEqualTo(400);
        assertThat(store.add(ApiKeyProvider.DTDD, RAW)).isEqualTo(400);
        assertThat(store.add(ApiKeyProvider.DTDD, null)).isEqualTo(400);
        assertThat(store.add(ApiKeyProvider.DTDD, "ddd_abcdefghijklmnop5678")).isEqualTo(204);
    }

    @Test
    void delete_removesTheKeyOfThatId_andUnknownIs404() {
        store.add(ApiKeyProvider.STEAM, RAW);
        String id = store.page(ApiKeyProvider.STEAM).keys().stream()
                .filter(k -> k.masked().equals("ABCD…6789")).findFirst().orElseThrow().id();

        assertThat(store.delete(ApiKeyProvider.DTDD, id)).isEqualTo(404);
        assertThat(store.delete(ApiKeyProvider.STEAM, id)).isEqualTo(204);
        assertThat(store.delete(ApiKeyProvider.STEAM, id)).isEqualTo(404);
        assertThat(store.page(ApiKeyProvider.STEAM).keys()).noneMatch(k -> k.id().equals(id));
    }
}
