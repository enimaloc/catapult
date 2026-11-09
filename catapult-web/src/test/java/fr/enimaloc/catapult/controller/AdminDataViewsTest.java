package fr.enimaloc.catapult.controller;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDataViewsTest {

    private static Map<String, Object> row() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "a~b%20c");
        row.put("name", "!lurk");
        row.put("enabled", null);
        row.put("user", "42");
        row.put("user_label", "enimaloc");
        return row;
    }

    @Test
    void table_showsEachLabelWithItsRelation_notAsAColumn() {
        var table = AdminDataViews.table(List.of(row()));

        assertThat(table.columns()).containsExactly("id", "name", "enabled", "user");
        assertThat(table.rows()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("a~b%20c");
            assertThat(r.cells()).containsExactly("a~b%20c", "!lurk", "—", "42 · enimaloc");
        });
        assertThat(AdminDataViews.table(List.of()).columns()).isEmpty();
    }

    @Test
    void editForm_leavesTheIdOut_andShowsCollectionsAside() {
        Map<String, Object> row = row();
        row.put("aliases", List.of(Map.of("id", "7", "label", "!l")));

        var form = AdminDataViews.form("chat-command", "a~b%20c", row);

        assertThat(form.creating()).isFalse();
        assertThat(form.fields()).containsExactly(
                new AdminDataViews.Field("name", "!lurk", null),
                new AdminDataViews.Field("enabled", "", null),
                new AdminDataViews.Field("user", "42", "enimaloc"));
        assertThat(form.collections()).containsExactly(Map.entry("aliases", List.of(new AdminDataViews.Link("7", "!l"))));
    }

    @Test
    void newForm_isLaidOutOnARow_withEveryValueBlank_andTheId() {
        var form = AdminDataViews.form("chat-command", null, row());

        assertThat(form.creating()).isTrue();
        assertThat(form.fields()).extracting(AdminDataViews.Field::name).containsExactly("id", "name", "enabled", "user");
        assertThat(form.fields()).allMatch(f -> f.value().isEmpty() && f.label() == null);
        assertThat(form.collections()).isEmpty();
    }
}
