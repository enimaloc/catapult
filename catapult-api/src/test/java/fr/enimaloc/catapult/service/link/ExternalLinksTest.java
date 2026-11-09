package fr.enimaloc.catapult.service.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ExternalLinksTest {

    @Test
    void igdbGameIsTheGamesPage() {
        assertEquals("https://www.igdb.com/games/valheim", ExternalLinks.igdbGame("valheim"));
    }

    @Test
    void blankOrMissingSlugHasNoPage() {
        assertNull(ExternalLinks.igdbGame(null));
        assertNull(ExternalLinks.igdbGame(""));
        assertNull(ExternalLinks.igdbGame("  "));
    }

    @Test
    void steamAppIsTheStorePage() {
        assertEquals("https://store.steampowered.com/app/892970", ExternalLinks.steamApp("892970"));
    }

    @Test
    void microsoftStoreProductIsTheStorePage() {
        assertEquals("https://www.microsoft.com/store/apps/9NBLGGH2JHXJ",
                ExternalLinks.microsoftStoreProduct("9NBLGGH2JHXJ"));
    }

    @Test
    void dtddMediaIsTheMediaPage() {
        assertEquals("https://www.doesthedogdie.com/media/16758", ExternalLinks.dtddMedia(16758));
    }

    @Test
    void youtubeVideoIsTheWatchPage() {
        assertEquals("https://www.youtube.com/watch?v=abc", ExternalLinks.youtubeVideo("abc"));
    }

    @Test
    void steamAppIdIsReadFromAnIdOrAStoreUrl() {
        assertEquals(Optional.of("440"), ExternalLinks.steamAppId("440"));
        assertEquals(Optional.of("440"),
                ExternalLinks.steamAppId("https://store.steampowered.com/app/440/Team_Fortress_2/"));
        assertEquals(Optional.of("440"), ExternalLinks.steamAppId("http://store.steampowered.com/app/440"));
        assertEquals(Optional.empty(), ExternalLinks.steamAppId("https://example.com/app/440"));
        assertEquals(Optional.empty(), ExternalLinks.steamAppId("abc"));
        assertEquals(Optional.empty(), ExternalLinks.steamAppId(""));
    }
}
