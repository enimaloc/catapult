package fr.enimaloc.catapult.service.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** The external service named after the host of a recorded exchange. */
class ExternalServicesTest {

    private final ExternalServices services = new ExternalServices("https://git.enimaloc.fr");

    @ParameterizedTest
    @CsvSource({
            "https://friends-public-service-prod.ol.epicgames.com/friends/api/v1/x/summary, epic",
            "https://api.epicgames.dev/epic/presence/internal/v1/_/, epic",
            "wss://connect.ol.epicgames.com, epic",
            "https://oauth.battle.net/token, battlenet",
            "https://eu.api.blizzard.com/x, battlenet",
            "https://www.bungie.net/Platform/Settings/, bungie",
            "https://osu.ppy.sh/api/v2/me, osu",
            "https://haapi.ankama.com/json/Ankama/v5/Account/Account, ankama",
            "https://api.twitch.tv/helix/users, twitch",
            "https://example.org/x, example.org",
    })
    void namedAfterTheHost(String url, String service) {
        assertThat(services.of(URI.create(url))).isEqualTo(service);
    }

    /** The browser's OBS and Twitchat exchanges carry these names, the ones the admin's service filter lists. */
    @Test
    void adminServicesListIncludesObsAndTwitchat() {
        assertThat(services.of(URI.create("obs://local"))).isEqualTo("obs");
        assertThat(services.of(URI.create("twitchat://obs"))).isEqualTo("twitchat");
    }
}
