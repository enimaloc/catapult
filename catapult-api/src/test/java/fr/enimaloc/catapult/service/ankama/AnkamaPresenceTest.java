package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.service.ankama.chat.ChatMessages.EndpointProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static fr.enimaloc.catapult.service.ankama.chat.ChatMessages.PRESENCE_OFFLINE;
import static fr.enimaloc.catapult.service.ankama.chat.ChatMessages.PRESENCE_ONLINE;
import static org.assertj.core.api.Assertions.assertThat;

class AnkamaPresenceTest {

    private static final int LAUNCHER = 102;
    private static final int CHAT = 99;

    private static EndpointProperties endpoint(int applicationId, Map<String, String> metadata) {
        return new EndpointProperties(applicationId, List.of("IN_LAUNCHER"), metadata);
    }

    private static EndpointProperties endpoint(int applicationId) {
        return endpoint(applicationId, Map.of());
    }

    @Test
    void wakfuNextToTheLauncher_isWakfu() {
        List<EndpointProperties> endpoints = List.of(endpoint(3), endpoint(LAUNCHER));

        assertThat(AnkamaPresence.currentGame(endpoints, PRESENCE_ONLINE)).isEqualTo(OptionalInt.of(3));
    }

    @Test
    void launcherOnly_isNoGame() {
        assertThat(AnkamaPresence.currentGame(List.of(endpoint(LAUNCHER)), PRESENCE_ONLINE)).isEmpty();
    }

    @Test
    void chatEndpoint_isNoGame() {
        List<EndpointProperties> endpoints = List.of(endpoint(CHAT), endpoint(LAUNCHER));

        assertThat(AnkamaPresence.currentGame(endpoints, PRESENCE_ONLINE)).isEmpty();
    }

    @Test
    void dofusWithServerMetadata_isDofus() {
        List<EndpointProperties> endpoints = List.of(
                endpoint(1, Map.of("SERVER_NAME", "Imagiro")),
                endpoint(LAUNCHER));

        assertThat(AnkamaPresence.currentGame(endpoints, PRESENCE_ONLINE)).isEqualTo(OptionalInt.of(1));
    }

    @Test
    void twoGames_theFirstListedWins() {
        List<EndpointProperties> endpoints = List.of(endpoint(22), endpoint(3), endpoint(LAUNCHER));

        assertThat(AnkamaPresence.currentGame(endpoints, PRESENCE_ONLINE)).isEqualTo(OptionalInt.of(22));
    }

    @Test
    void offline_isNoGame_whateverTheEndpoints() {
        List<EndpointProperties> endpoints = List.of(endpoint(3), endpoint(LAUNCHER));

        assertThat(AnkamaPresence.currentGame(endpoints, PRESENCE_OFFLINE)).isEmpty();
    }

    @Test
    void emptyList_isNoGame() {
        assertThat(AnkamaPresence.currentGame(List.of(), PRESENCE_ONLINE)).isEmpty();
    }
}
