package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import org.junit.jupiter.api.Test;

import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.NAME;
import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

class MockMetaTagStoreTest {
    private final MockMetaTagStore store = new MockMetaTagStore();

    @Test
    void startsWithAThemeColor_andListsByAttributeThenKey() {
        assertThat(store.list()).extracting(MetaTagDto::key).containsExactly("theme-color");
        assertThat(store.create(new MetaTagRequest(PROPERTY, "og:image", "https://x/y.png"))).isEqualTo(201);
        assertThat(store.create(new MetaTagRequest(NAME, "author", "x"))).isEqualTo(201);
        assertThat(store.list()).extracting(MetaTagDto::key).containsExactly("author", "theme-color", "og:image");
    }

    @Test
    void refusesAsCatapultApiDoes() {
        assertThat(store.create(new MetaTagRequest(NAME, "Theme-Color", "#fff"))).isEqualTo(409);
        assertThat(store.create(new MetaTagRequest(PROPERTY, "theme-color", "#fff"))).isEqualTo(201);
        assertThat(store.create(new MetaTagRequest(NAME, "description", "x"))).isEqualTo(400);
        assertThat(store.create(new MetaTagRequest(NAME, "a b", "x"))).isEqualTo(400);
        assertThat(store.create(new MetaTagRequest(NAME, "k", "a\nb"))).isEqualTo(400);
        assertThat(store.create(new MetaTagRequest(NAME, "k", " "))).isEqualTo(400);
        assertThat(store.create(new MetaTagRequest(null, "k", "x"))).isEqualTo(400);
    }

    @Test
    void updatesAndDeletes() {
        long id = store.list().getFirst().id();
        assertThat(store.update(id, new MetaTagRequest(NAME, "theme-color", "#fff"))).isEqualTo(200);
        assertThat(store.list().getFirst().content()).isEqualTo("#fff");
        assertThat(store.update(99, new MetaTagRequest(NAME, "k", "x"))).isEqualTo(404);
        assertThat(store.delete(id)).isEqualTo(204);
        assertThat(store.delete(id)).isEqualTo(404);
        assertThat(store.list()).isEmpty();
    }
}
