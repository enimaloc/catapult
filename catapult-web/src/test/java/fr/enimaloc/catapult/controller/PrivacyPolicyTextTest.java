package fr.enimaloc.catapult.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** What the privacy policy says about the personal stats matches what the cache does. */
class PrivacyPolicyTextTest {

    private static String page(String lang) throws IOException {
        return new ClassPathResource("lang/privacy/" + lang + ".html").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
    }

    @Test
    void statsAreRefreshedNotExpired_andKeptUntilUnlinkOrDeletion() throws IOException {
        String en = page("en");
        assertThat(en).doesNotContain("cached for 15 minutes at most")
                .contains("refreshed every 15 minutes at most")
                .contains("kept until you unlink the platform or delete your account")
                .contains("while the platform cannot be reached");

        String fr = page("fr");
        assertThat(fr).doesNotContain("gardées en cache 15 minutes au plus")
                .contains("rafraîchies toutes les 15 minutes au plus")
                .contains("jusqu'à la déliaison de la plateforme ou la suppression de votre compte")
                .contains("quand la plateforme est injoignable");
    }

    @Test
    void theFrenchTransfersNameTheRetroAchievementsData() throws IOException {
        assertThat(page("fr")).doesNotContain("ceux de RetroAchievements")
                .contains("Votre nom d'utilisateur et votre clé RetroAchievements ne sont transmis qu'à "
                        + "RetroAchievements.");
    }
}
