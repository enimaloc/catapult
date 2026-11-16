package fr.enimaloc.catapult.service.epic;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EpicPresenceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String LAUNCHER = "{\"status\":\"online\",\"party\":{\"joinable\":false,\"invitable\":true},"
            + "\"ns\":\"_\",\"conns\":[{\"id\":\"x\",\"props\":{}}]}";

    private static final String FALL_GUYS_PROPS = "\"props\":{\"EOS_Lobby\":\"{\\\"version\\\":3}\","
            + "\"EOS_Platform\":\"WIN\",\"EOS_ProductName\":\"Fall Guys\"}";

    private static final String FALL_GUYS = "{\"productId\":\"d42d7101bb7a41c09a810ee418b7d3d0\","
            + "\"appId\":\"fghi45677JW4O6a40cxsOpscCLcr3Z3j\",\"status\":\"online\",\"activity\":{},"
            + "\"ns\":\"8bedfebaf56f406ebab78986ada3f9b3\"," + FALL_GUYS_PROPS + ",\"conns\":[]}";

    private static final String FALL_GUYS_IN_LOBBY = "{\"productId\":\"d42d7101bb7a41c09a810ee418b7d3d0\","
            + "\"appId\":\"fghi45677JW4O6a40cxsOpscCLcr3Z3j\",\"status\":\"online\",\"activity\":{},"
            + "\"ns\":\"8bedfebaf56f406ebab78986ada3f9b3\",\"props\":{\"EOS_Lobby\":\"{\\\"version\\\":3,"
            + "\\\"id\\\":\\\"3bafe1761e704257b09c1d173e47c9e5\\\",\\\"joinable\\\":\\\"JoinDisabled\\\"}\","
            + "\"EOS_Platform\":\"WIN\",\"EOS_ProductName\":\"Fall Guys\"},\"conns\":[]}";

    private static JsonNode payload(String status, String... perNs) {
        return MAPPER.readTree("{\"accountId\":\"51752b8577e341a6a96f95f6a95f38d5\",\"status\":\"" + status
                + "\",\"perNs\":[" + String.join(",", perNs) + "]}");
    }

    @Test
    void launcherOnly_isNoGame() {
        assertThat(EpicPresence.currentGame(payload("online", LAUNCHER))).isEmpty();
    }

    @Test
    void fallGuys_isItsProductId() {
        assertThat(EpicPresence.currentGame(payload("online", FALL_GUYS, LAUNCHER)))
                .contains(new EpicGame("d42d7101bb7a41c09a810ee418b7d3d0", "Fall Guys", true));
    }

    @Test
    void fallGuysInALobby_isStillFallGuys() {
        assertThat(EpicPresence.currentGame(payload("online", FALL_GUYS_IN_LOBBY, LAUNCHER)))
                .contains(new EpicGame("d42d7101bb7a41c09a810ee418b7d3d0", "Fall Guys", true));
    }

    @Test
    void offline_isNoGame() {
        assertThat(EpicPresence.currentGame(MAPPER.readTree("{\"status\":\"offline\",\"perNs\":[]}"))).isEmpty();
        assertThat(EpicPresence.currentGame(payload("offline", FALL_GUYS))).isEmpty();
    }

    @Test
    void missingPerNs_isNoGame() {
        assertThat(EpicPresence.currentGame(MAPPER.readTree("{\"status\":\"online\"}"))).isEmpty();
    }

    @Test
    void nonEosGame_isItsPrefixedNamespace() {
        String game = "{\"ns\":\"abc\",\"status\":\"online\",\"props\":{\"X_NonEOS_ProductName\":\"Some Game\"}}";
        assertThat(EpicPresence.currentGame(payload("online", game, LAUNCHER)))
                .contains(new EpicGame("ns:abc", "Some Game", false));
    }

    private static final String RUNNING_APP_ID = "magnolia:a379f050fa3d47e4a0fa696b0ef744d5:Magnolia";

    private static String launcherRunning(String props, boolean manual) {
        return "{\"status\":\"online\",\"ns\":\"_\"" + (manual ? ",\"statusManual\":true" : "")
                + ",\"conns\":[{\"id\":\"x\",\"props\":" + props + "}]}";
    }

    @Test
    void launcherRunningApp_isAnAppGame() {
        String launcher = launcherRunning("{\"running_app_id\":\"" + RUNNING_APP_ID
                + "\",\"running_app_name\":\"The Witness\"}", false);
        assertThat(EpicPresence.currentGame(payload("online", launcher)))
                .contains(new EpicGame("app:" + RUNNING_APP_ID, "The Witness", false));
    }

    @Test
    void launcherRunningAppWithoutName_isNamedByItsId() {
        String launcher = launcherRunning("{\"running_app_id\":\"" + RUNNING_APP_ID + "\"}", false);
        assertThat(EpicPresence.currentGame(payload("online", launcher)))
                .contains(new EpicGame("app:" + RUNNING_APP_ID, RUNNING_APP_ID, false));
    }

    @Test
    void launcherRunningApp_losesToAnEosGame() {
        String launcher = launcherRunning("{\"running_app_id\":\"" + RUNNING_APP_ID + "\"}", false);
        assertThat(EpicPresence.currentGame(payload("online", launcher, FALL_GUYS)))
                .contains(new EpicGame("d42d7101bb7a41c09a810ee418b7d3d0", "Fall Guys", true));
    }

    @Test
    void launcherRunningApp_withManualStatus_isNoGame() {
        String launcher = launcherRunning("{\"running_app_id\":\"" + RUNNING_APP_ID + "\"}", true);
        assertThat(EpicPresence.currentGame(payload("online", launcher))).isEmpty();
    }

    @Test
    void launcherWithBlankRunningApp_isNoGame() {
        assertThat(EpicPresence.currentGame(payload("online", launcherRunning("{\"running_app_id\":\" \"}", false))))
                .isEmpty();
    }

    @Test
    void manualStatus_isNoGame() {
        String game = "{\"productId\":\"p1\",\"ns\":\"abc\",\"statusManual\":true,\"status\":\"online\","
                + "\"props\":{\"EOS_ProductName\":\"Manual\"}}";
        assertThat(EpicPresence.currentGame(payload("online", game))).isEmpty();
    }

    @Test
    void twoGames_theFirstListedWins() {
        String first = "{\"productId\":\"p1\",\"ns\":\"n1\",\"props\":{\"EOS_ProductName\":\"First\"}}";
        String second = "{\"productId\":\"p2\",\"ns\":\"n2\",\"props\":{\"EOS_ProductName\":\"Second\"}}";
        assertThat(EpicPresence.currentGame(payload("online", first, second)))
                .contains(new EpicGame("p1", "First", true));
    }

    @Test
    void missingProductName_fallsBackToTheProductId() {
        String game = "{\"productId\":\"p1\",\"ns\":\"n1\",\"props\":{}}";
        assertThat(EpicPresence.currentGame(payload("online", game))).contains(new EpicGame("p1", "p1", true));
    }

    @Test
    void accountId_isReadFromThePayload() {
        assertThat(EpicPresence.accountId(payload("online", LAUNCHER))).isEqualTo("51752b8577e341a6a96f95f6a95f38d5");
    }
}
