package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.SpaPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SiteCatalogTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "privacy", "channels", "channel"})
    void knowsEveryPage(String path) {
        assertThat(SiteCatalog.isPage(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nope", "Privacy", "channel/enimaloc"})
    void rejectsAnythingElse(String path) {
        assertThat(SiteCatalog.isPage(path)).isFalse();
    }

    @Test
    void onlyTheChannelPageTakesAParameter() {
        assertThat(SiteCatalog.PAGES).filteredOn(SpaPage::isDynamic).extracting(SpaPage::getId).containsExactly("channel");
    }

    @Test
    void everyPageIsServedUnderSpa() {
        assertThat(SiteCatalog.PAGES).allSatisfy(page -> assertThat(page.getTemplateUrl()).startsWith("/spa/"));
    }
}
