package fr.enimaloc.catapult.service.igdb;

import org.junit.jupiter.api.Test;
import proto.Website;
import proto.WebsiteType;

import static org.assertj.core.api.Assertions.assertThat;

class IgdbWebsiteKeysTest {

    private static Website typed(long id, String name) {
        return Website.newBuilder()
            .setType(WebsiteType.newBuilder().setId(id).setType(name))
            .setUrl("https://example.com")
            .build();
    }

    @Test
    void legacyTypeIdsKeepTheOldCategoryKeys() {
        assertThat(IgdbWebsiteKeys.key(typed(1L, "Official Website"))).isEqualTo("official");
        assertThat(IgdbWebsiteKeys.key(typed(13L, "Steam"))).isEqualTo("steam");
        assertThat(IgdbWebsiteKeys.key(typed(16L, "Epic"))).isEqualTo("epicgames");
    }

    @Test
    void newerTypesFallBackToASlugOfTheirName() {
        assertThat(IgdbWebsiteKeys.key(typed(22L, "Xbox"))).isEqualTo("xbox");
        assertThat(IgdbWebsiteKeys.key(typed(99L, "App Store (Vision Pro)"))).isEqualTo("app_store_vision_pro");
    }

    @Test
    void untypedEntriesUseTheCategoryNullSentinel() {
        assertThat(IgdbWebsiteKeys.key(Website.newBuilder().setUrl("https://example.com").build()))
            .isEqualTo(IgdbWebsiteKeys.UNTYPED_KEY);
        assertThat(IgdbWebsiteKeys.key(typed(99L, ""))).isEqualTo(IgdbWebsiteKeys.UNTYPED_KEY);
    }
}
