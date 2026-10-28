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
}
