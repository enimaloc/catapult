package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SteamIADisclosureTest {

    private static String descriptorsBlock(String heading, String description) {
        return """
                <div id="game_area_content_descriptors">
                  <h2>%s</h2>
                  <p><i>%s</i></p>
                </div>""".formatted(heading, description);
    }

    private static final String MATURE = descriptorsBlock("Mature Content Description", "Some violence.");
    private static final String AI = descriptorsBlock("AI Generated Content Disclosure",
            "  We used AI for some voice lines.  ");

    @Test
    void detectsTheHeadingInAnySupportedLanguage() {
        assertThat(SteamIADisclosure.hasDisclosure("<h2>AI Generated Content Disclosure</h2>")).isTrue();
        assertThat(SteamIADisclosure.hasDisclosure("<h2>Divulgation de contenu généré par IA</h2>")).isTrue();
        assertThat(SteamIADisclosure.hasDisclosure("<h2>AI生成コンテンツの開示</h2>")).isTrue();
        assertThat(SteamIADisclosure.hasDisclosure("<h2>Mature Content Description</h2>")).isFalse();
    }

    @Test
    void detectsTheHeadingOfOneLanguage() {
        String french = "<h2>Divulgation de contenu généré par IA</h2>";

        assertThat(SteamIADisclosure.hasDisclosure(french, Locale.FRENCH)).isTrue();
        assertThat(SteamIADisclosure.hasDisclosure(french, Locale.ENGLISH)).isFalse();
        assertThat(SteamIADisclosure.hasDisclosure(french, SteamLanguage.FRENCH)).isTrue();
    }

    @Test
    void extractsTheDevelopersDescription_whateverTheBlockOrder() {
        assertThat(SteamIADisclosure.extractDeveloperDescription(MATURE + AI)).contains("We used AI for some voice lines.");
        assertThat(SteamIADisclosure.extractDeveloperDescription(AI + MATURE)).contains("We used AI for some voice lines.");
    }

    @Test
    void noDescriptionWithoutDisclosure() {
        assertThat(SteamIADisclosure.extractDeveloperDescription(MATURE)).isEmpty();
    }

    @Test
    void noDescriptionWhenTheDisclosureHasNoText() {
        assertThat(SteamIADisclosure.extractDeveloperDescription(
                descriptorsBlock("AI Generated Content Disclosure", "   "))).isEmpty();
        assertThat(SteamIADisclosure.extractDeveloperDescription("""
                <div id="game_area_content_descriptors"><h2>AI Generated Content Disclosure</h2><p>plain</p></div>"""))
                .isEmpty();
        assertThat(SteamIADisclosure.extractDeveloperDescription("""
                <div id="game_area_content_descriptors"><p><i>no heading</i></p></div>
                <h2>AI Generated Content Disclosure</h2>""")).isEmpty();
    }
}
