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

    @Test
    void keywords_areNormalizedSortedAndDeduplicated() {
        assertThat(store.addKeyword("violence", "  Zed ")).isEqualTo(204);
        assertThat(store.addKeyword("violence", "zed")).isEqualTo(204);

        assertThat(store.keywords("violence")).containsExactly("blood", "gore", "zed");
        assertThat(store.removeKeyword("violence", "ZED")).isEqualTo(204);
        assertThat(store.removeKeyword("violence", "never-there")).isEqualTo(204);
        assertThat(store.keywords("violence")).containsExactly("blood", "gore");
    }

    @Test
    void keywords_refuseTooShortTooLongAndUnknown() {
        assertThat(store.addKeyword("violence", "a")).isEqualTo(400);
        assertThat(store.addKeyword("violence", "x".repeat(51))).isEqualTo(400);
        assertThat(store.addKeyword("nope", "gore")).isEqualTo(404);
        assertThat(store.removeKeyword("nope", "gore")).isEqualTo(404);
        assertThat(store.keywords("nope")).isNull();
    }

    @Test
    void test_matchesTheSavedKeywordsAndTheDraftAgainstTheAppsNotes() {
        var result = store.test("violence", "730", "Blood");

        assertThat(result.matchedKeywords()).containsExactly("blood", "gore");
        assertThat(result.draftKeywordMatch()).isTrue();
        assertThat(result.notes()).isNotBlank();
        assertThat(store.test("violence", "730", "spiders").draftKeywordMatch()).isFalse();
        assertThat(store.test("violence", "730", "").draftKeywordMatch()).isNull();
        assertThat(store.test("violence", "999", null).matchedKeywords()).isEmpty();
        assertThat(store.test("nope", "730", null)).isNull();
    }
}
