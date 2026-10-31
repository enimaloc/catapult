package fr.enimaloc.catapult.service.mock;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockGroupsStoreTest {
    private final MockGroupsStore store = new MockGroupsStore();

    @Test
    void create_answers201_409ForADuplicateKey_400ForABlankOne() {
        assertThat(store.create(" fresh ", "Fresh", null)).isEqualTo(201);
        assertThat(store.find("fresh")).isPresent();
        assertThat(store.create("fresh", "Other", null)).isEqualTo(409);
        assertThat(store.create("  ", "x", null)).isEqualTo(400);
    }

    @Test
    void create_aBlankNameFallsBackToTheKey() {
        store.create("slug", " ", "d");

        assertThat(store.find("slug").orElseThrow().name()).isEqualTo("slug");
        assertThat(store.find("slug").orElseThrow().description()).isEqualTo("d");
    }

    @Test
    void rename_keepsTheNameWhenBlank_andReplacesTheDescription() {
        store.create("g", "Old", "old desc");
        UUID id = store.find("g").orElseThrow().id();

        assertThat(store.rename(id, " ", null)).isEqualTo(204);
        var group = store.find("g").orElseThrow();
        assertThat(group.name()).isEqualTo("Old");
        assertThat(group.description()).isNull();

        store.rename(id, "New", "d");
        assertThat(store.find("g").orElseThrow().name()).isEqualTo("New");
        assertThat(store.rename(UUID.randomUUID(), "x", null)).isEqualTo(404);
    }

    @Test
    void members_areCounted_once_andKeysOfListsTheGroupsOfAMember() {
        store.create("a", "A", null);
        store.create("b", "B", null);
        UUID a = store.find("a").orElseThrow().id();
        UUID b = store.find("b").orElseThrow().id();
        UUID member = UUID.randomUUID();

        assertThat(store.addMember(a, member)).isEqualTo(204);
        assertThat(store.addMember(a, member)).isEqualTo(204);
        store.addMember(b, member);

        assertThat(store.find("a").orElseThrow().memberCount()).isEqualTo(1);
        assertThat(store.keysOf(member)).containsExactly("a", "b");
        assertThat(store.removeMember(a, member)).isEqualTo(204);
        assertThat(store.keysOf(member)).containsExactly("b");
        assertThat(store.keysOf(UUID.randomUUID())).isEmpty();
        assertThat(store.addMember(UUID.randomUUID(), member)).isEqualTo(404);
        assertThat(store.removeMember(UUID.randomUUID(), member)).isEqualTo(404);
    }

    @Test
    void delete_removesTheGroup_404WhenUnknown() {
        store.create("gone", "Gone", null);
        UUID id = store.find("gone").orElseThrow().id();

        assertThat(store.delete(id)).isEqualTo(204);
        assertThat(store.find("gone")).isEmpty();
        assertThat(store.delete(id)).isEqualTo(404);
    }
}
