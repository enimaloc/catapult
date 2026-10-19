package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockTwStoreTest {
    private final MockTwStore store = new MockTwStore();

    @Test
    void create_listsTheDefinitionBySortOrder_andFindsIt() {
        assertThat(store.create("aaa_first", "  First  ", 0)).isEqualTo(201);

        assertThat(store.list().getFirst()).isEqualTo(new TwDefinitionDto("aaa_first", "First", null, true, 0));
        assertThat(store.find("aaa_first")).isPresent();
        assertThat(store.find("nope")).isEmpty();
    }

    @Test
    void create_refusesABadIdOrLabel_andADuplicate() {
        assertThat(store.create("Bad Id", "L", 0)).isEqualTo(400);
        assertThat(store.create("a".repeat(41), "L", 0)).isEqualTo(400);
        assertThat(store.create(null, "L", 0)).isEqualTo(400);
        assertThat(store.create("ok", " ", 0)).isEqualTo(400);
        assertThat(store.create("ok", "x".repeat(81), 0)).isEqualTo(400);
        assertThat(store.create("violence", "Again", 0)).isEqualTo(409);
        assertThat(store.find("ok")).isEmpty();
    }

    @Test
    void rebuild_isAccepted() {
        assertThat(store.rebuild()).isEqualTo(202);
    }
}
