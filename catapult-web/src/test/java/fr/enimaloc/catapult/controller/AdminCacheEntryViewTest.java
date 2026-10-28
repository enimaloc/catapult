package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCacheEntryViewTest {

    @Test
    void aRecord_becomesItsFields() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", "Doom");
        detail.put("rating", null);

        var view = AdminCacheEntryView.of(new CacheEntryDetailDto("1001", detail, 60L));

        assertThat(view.fields()).containsExactly(
                new AdminCacheEntryView.Field("name", "Doom"), new AdminCacheEntryView.Field("rating", "—"));
        assertThat(view.rows()).isEmpty();
        assertThat(view.expiresInSeconds()).isEqualTo(60L);
    }

    @Test
    void aListOfRecords_becomesATableOverEveryKey() {
        var view = AdminCacheEntryView.of(new CacheEntryDetailDto("u", List.of(
                Map.of("name", "!lurk"), new LinkedHashMap<>(Map.of("name", "!so", "enabled", true))), null));

        assertThat(view.columns()).containsExactlyInAnyOrder("name", "enabled");
        assertThat(view.rows()).hasSize(2);
        assertThat(view.rows().getFirst().get(view.columns().indexOf("enabled"))).isEqualTo("—");
    }

    @Test
    void nothingBehindTheEntry_isEmpty() {
        assertThat(AdminCacheEntryView.of(new CacheEntryDetailDto("k", null, null)).empty()).isTrue();
        assertThat(AdminCacheEntryView.of(null)).isNull();
    }

    @Test
    void aBareValue_isOneField() {
        assertThat(AdminCacheEntryView.of(new CacheEntryDetailDto("k", "v", null)).fields())
                .containsExactly(new AdminCacheEntryView.Field("value", "v"));
    }
}
