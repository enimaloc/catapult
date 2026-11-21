package fr.enimaloc.catapult.api.userapi;

import org.junit.jupiter.api.Test;

import static fr.enimaloc.catapult.api.userapi.GameInfoAssembler.LinkStyle.CURRENT;
import static fr.enimaloc.catapult.api.userapi.GameInfoAssembler.LinkStyle.LEGACY;
import static org.assertj.core.api.Assertions.assertThat;

class MoreLinksTest {

    private static final String BASE = "https://catapult.test";

    @Test
    void current_pointsAtApiGamesAndTheCallersBindings() {
        MoreLinks links = new MoreLinks(BASE, CURRENT);

        assertThat(links.to(MoreLinks.Kind.IGDB, "72")).isEqualTo(BASE + "/api/games/igdb/72");
        assertThat(links.to(MoreLinks.Kind.STEAM, "620")).isEqualTo(BASE + "/api/games/steam/620");
        assertThat(links.to(MoreLinks.Kind.XBOX, "9X")).isEqualTo(BASE + "/api/games/xbox/9X");
        assertThat(links.to(MoreLinks.Kind.DTDD, 99L)).isEqualTo(BASE + "/api/games/dtdd/99");
        assertThat(links.to(MoreLinks.Kind.BINDING, "b")).isEqualTo(BASE + "/api/me/bindings/b");
    }

    @Test
    void legacy_keepsTheFrozenApiUserRoutes() {
        MoreLinks links = new MoreLinks(BASE, LEGACY);

        assertThat(links.to(MoreLinks.Kind.IGDB, "72")).isEqualTo(BASE + "/api/user/igdb/72");
        assertThat(links.to(MoreLinks.Kind.STEAM, "620")).isEqualTo(BASE + "/api/user/steam/620");
        assertThat(links.to(MoreLinks.Kind.XBOX, "9X")).isEqualTo(BASE + "/api/user/xbox/9X");
        assertThat(links.to(MoreLinks.Kind.DTDD, 99L)).isEqualTo(BASE + "/api/user/dtdd/99");
        assertThat(links.to(MoreLinks.Kind.BINDING, "b")).isEqualTo(BASE + "/api/user/catapult/b");
    }

    /** Routes added in version 3 name it in the link: opened as is (OBS, a browser), no header carries it. */
    @Test
    void sinceV3Kinds_selectVersion3InTheQuery() {
        MoreLinks links = new MoreLinks(BASE, CURRENT);

        assertThat(links.to(MoreLinks.Kind.HLTB, "72")).isEqualTo(BASE + "/api/games/hltb/72?version=3");
        assertThat(links.to(MoreLinks.Kind.PROTONDB, "620")).isEqualTo(BASE + "/api/games/protondb/620?version=3");
        assertThat(links.to(MoreLinks.Kind.STEAMDECK, "620")).isEqualTo(BASE + "/api/games/steamdeck/620?version=3");
        assertThat(links.to(MoreLinks.Kind.STEAMGRIDDB, 5L))
                .isEqualTo(BASE + "/api/games/steamgriddb/5?version=3");
        assertThat(links.to(MoreLinks.Kind.PCGAMINGWIKI, 42L))
                .isEqualTo(BASE + "/api/games/pcgamingwiki/42?version=3");
    }
}
