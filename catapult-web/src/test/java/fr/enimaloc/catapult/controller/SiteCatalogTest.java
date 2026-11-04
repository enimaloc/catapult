package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.dto.index.SpaPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SiteCatalogTest {

    @ParameterizedTest
    @ValueSource(strings = {"messages", "messages_en", "messages_fr"})
    void everyLandingCardHasItsTextInEveryLanguage(String bundle) throws IOException {
        Properties messages = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/lang/" + bundle + ".properties")) {
            messages.load(in);
        }

        Stream<String> features = SiteCatalog.FEATURES.stream().flatMap(f -> Stream.of(f.titleKey(), f.descriptionKey()));
        Stream<String> platforms = SiteCatalog.PLATFORMS.stream().flatMap(p -> Stream.of(p.titleKey(), p.descriptionKey()));
        assertThat(Stream.concat(features, platforms)).allSatisfy(key -> assertThat(messages).containsKey(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "privacy", "changelog", "docs/api", "channel", "admin", "notifications", "premium", "login", "join"})
    void knowsEveryPage(String path) {
        assertThat(SiteCatalog.isPage(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nope", "Privacy", "channel/enimaloc", "channels"})
    void rejectsAnythingElse(String path) {
        assertThat(SiteCatalog.isPage(path)).isFalse();
    }

    @Test
    void onlyTheChannelPagesAndHelpTakeAParameter() {
        assertThat(SiteCatalog.PAGES).filteredOn(SpaPage::isDynamic).extracting(SpaPage::getId).containsExactly("channel", "channel-dock", "help");
    }

    @Test
    void everyPageIsServedUnderSpa() {
        assertThat(SiteCatalog.PAGES).allSatisfy(page -> assertThat(page.getTemplateUrl()).startsWith("/spa/"));
    }

    @Test
    void onlyThePublicPagesAreIndexed() {
        assertThat(SiteCatalog.PAGES).filteredOn(SpaPage::isIndexed).extracting(SpaPage::getId)
                .containsExactly("", "privacy", "changelog", "docs/api");
    }

    @Test
    void page_findsAPageByItsId() {
        assertThat(SiteCatalog.page("privacy")).map(SpaPage::getTitleKey).contains("page.title.privacy");
        assertThat(SiteCatalog.page("nope")).isEmpty();
    }
}
