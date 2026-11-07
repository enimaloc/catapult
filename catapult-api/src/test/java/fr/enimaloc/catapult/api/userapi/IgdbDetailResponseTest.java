package fr.enimaloc.catapult.api.userapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IgdbDetailResponseTest {

    private static final String BASE = "https://b";
    private static final MoreLinks LINKS = new MoreLinks(BASE, GameInfoAssembler.LinkStyle.LEGACY);

    private static Map<String, Object> websitesOf(String... keyValues) {
        Map<String, Object> websites = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            websites.put(keyValues[i], keyValues[i + 1]);
        }
        return new IgdbDetailResponse(LINKS, "s", null, null, null, List.of(), null, null, null, null, null,
                websites, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, List.of(), List.of()).websites();
    }

    private static void assertSteamLink(Object value, String url, String more) {
        IgdbDetailResponse.WebsiteLink link = assertInstanceOf(IgdbDetailResponse.WebsiteLink.class, value);
        assertEquals(url, link.url());
        assertEquals(more, link.more());
    }

    @Test
    void untypedWebsiteNeverReplacesATypedOne() {
        for (Map<String, Object> websites : List.of(
                websitesOf("category_null", "https://store.steampowered.com/app/1", "steam", "892970"),
                websitesOf("steam", "892970", "category_null", "https://store.steampowered.com/app/1"))) {
            assertSteamLink(websites.get("steam"), "https://store.steampowered.com/app/892970",
                    BASE + ApiV2.PATH + "/steam/892970");
        }
    }

    @Test
    void untypedWebsiteFillsAFreeKey() {
        assertEquals("https://www.gog.com/game/x", websitesOf("category_null", "https://www.gog.com/game/x").get("gog"));
    }

    @Test
    void steamWebsiteGivenAsAStoreUrlKeepsOneStoreLink() {
        assertSteamLink(websitesOf("steam", "https://store.steampowered.com/app/892970/Valheim/").get("steam"),
                "https://store.steampowered.com/app/892970", BASE + ApiV2.PATH + "/steam/892970");
    }

    @Test
    void steamWebsiteWithoutAnAppIdHasNoMoreLink() {
        assertSteamLink(websitesOf("steam", "https://example.com/valheim").get("steam"),
                "https://example.com/valheim", null);
    }

    @Test
    void steamWebsiteWithoutAnAppIdSerializesNoPath() {
        assertNull(new IgdbDetailResponse.WebsiteLink("u", LINKS, null, null).more());
    }
}
