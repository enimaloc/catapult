package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.service.http.ApiClient;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Each ApiService call must reach the matching catapult-api endpoint with the right body. */
class RealApiServiceTest {

    private static final String API = "http://api.test";

    private MockRestServiceServer api;
    private RealApiService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        api = MockRestServiceServer.bindTo(builder).build();
        service = new RealApiService(new ApiClient(API, builder));
    }

    @AfterEach
    void verifyEveryExpectedCallWasMade() {
        api.verify();
    }

    private ResponseActions expect(HttpMethod httpMethod, String path) {
        return api.expect(requestTo(API + path)).andExpect(method(httpMethod));
    }

    private void expectPost(String path) {
        expect(HttpMethod.POST, path).andExpect(content().string("")).andRespond(withNoContent());
    }

    private void expectPost(String path, String json) {
        expect(HttpMethod.POST, path).andExpect(content().json(json, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withNoContent());
    }

    private void respond(HttpMethod httpMethod, String path, String json) {
        expect(httpMethod, path).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    // --- reads ---------------------------------------------------------------------------------

    @Test
    void exchangeCode() {
        respond(HttpMethod.POST, "/api/auth/exchange?code=abc", "{\"token\":\"jwt\"}");

        assertThat(service.exchangeCode("abc")).isEqualTo(new TokenResponse("jwt"));
    }

    @Test
    void channelList() {
        respond(HttpMethod.GET, "/api/channel", "{\"ownTwitchId\":\"1\",\"channels\":[]}");

        assertThat(service.channelList()).isInstanceOf(ChannelListResponse.class);
    }

    @Test
    void channelPage_withoutFilter() {
        respond(HttpMethod.GET, "/api/channels/enimaloc?page=2", "{}");

        service.channelPage("enimaloc", 2, null, null);
    }

    @Test
    void channelPage_statusFilterWinsOverSource() {
        respond(HttpMethod.GET, "/api/channels/enimaloc?page=0&status=AUTO", "{}");

        service.channelPage("enimaloc", 0, "AUTO", "STEAM");
    }

    @Test
    void channelPage_sourceFilter() {
        respond(HttpMethod.GET, "/api/channels/enimaloc?page=0&source=STEAM", "{}");

        service.channelPage("enimaloc", 0, " ", "STEAM");
    }

    @Test
    void channelPage_blankFiltersAreDropped() {
        respond(HttpMethod.GET, "/api/channels/enimaloc?page=0", "{}");

        service.channelPage("enimaloc", 0, "", " ");
    }

    @Test
    void searchGames() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/games/search?q=doom%20eternal", "[{\"id\":\"1\"}]");

        assertThat(service.searchGames("enimaloc", "doom eternal")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(1);
    }

    @Test
    void minecraftStatus() {
        respond(HttpMethod.GET, "/api/connect/minecraft", "{\"status\":\"PENDING\",\"minecraftName\":\"Steve\"}");

        assertThat(service.minecraftStatus("enimaloc")).isEqualTo(new LinkStateResponse("PENDING", "Steve", null));
    }

    @Test
    void obsConnection() {
        respond(HttpMethod.GET, "/api/me/obs", "{\"obsHost\":\"10.0.0.2\",\"obsPort\":4456,\"obsPassword\":\"pw\"}");

        assertThat(service.obsConnection()).isEqualTo(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw"));
    }

    @Test
    void obsConnection_noContentIsNull() {
        expect(HttpMethod.GET, "/api/me/obs").andRespond(withNoContent());

        assertThat(service.obsConnection()).isNull();
    }

    @Test
    void channelSettings() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/settings", """
                {"cclFeatureEnabled":true,"blockedCcls":[],"noGameCcls":[],"applyDefaultOnStreamStart":false,
                 "applyDefaultOnNoGame":false,"applyDefaultOnStreamEnd":false,"incompleteFallbackCcls":[],
                 "availableCcls":[],"twFeatureEnabled":false,"blockedTws":[],"availableTws":[]}""");

        assertThat(service.channelSettings("enimaloc")).isInstanceOf(UserSettingsDto.class)
                .extracting(UserSettingsDto::cclFeatureEnabled).isEqualTo(true);
    }

    @Test
    void dtddMappingStatus() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/dtdd-mapping", "{\"igdbId\":\"42\",\"canValidateDirectly\":false}");

        assertThat(service.dtddMappingStatus("enimaloc")).extracting(DtddMappingStatusDto::igdbId).isEqualTo("42");
    }

    @Test
    void dtddSearch() {
        respond(HttpMethod.GET, "/api/channel/dtdd-mapping/search?q=doom", "{\"results\":[]}");

        assertThat(service.dtddSearch("doom")).isEqualTo(new SearchResponse(java.util.List.of()));
    }

    // --- mutations -----------------------------------------------------------------------------

    @Test
    void toggleBot() {
        expectPost("/api/channels/enimaloc/settings/bot");
        service.toggleBot("enimaloc");
    }

    @Test
    void recheckGame() {
        expectPost("/api/channels/enimaloc/game/recheck");
        service.recheckGame("enimaloc");
    }

    @Test
    void cclToggle() {
        expectPost("/api/channels/enimaloc/bindings/b1/ccl-toggle", "{\"enabled\":true}");
        service.cclToggle("enimaloc", "b1", true);
    }

    @Test
    void ignoredToggle() {
        expectPost("/api/channels/enimaloc/bindings/b1/ignored-toggle", "{\"ignored\":false}");
        service.ignoredToggle("enimaloc", "b1", false);
    }

    @Test
    void deleteBinding() {
        expectPost("/api/channels/enimaloc/bindings/b1/delete");
        service.deleteBinding("enimaloc", "b1");
    }

    @Test
    void updateBinding() {
        expectPost("/api/channels/enimaloc/bindings/b1",
                "{\"twitchGameId\":\"7\",\"twitchGameName\":\"Doom\",\"ccls\":[\"Gore\"]}");
        service.updateBinding("enimaloc", "b1", "7", "Doom", Set.of("Gore"));
    }

    @Test
    void saveTws() {
        expectPost("/api/channel/bindings/b1/tws", "{\"tws\":[\"spiders\"]}");
        service.saveTws("b1", Set.of("spiders"));
    }

    @Test
    void resetTws() {
        expectPost("/api/channel/bindings/b1/tws/reset");
        service.resetTws("b1");
    }

    @Test
    void toggleTwEnabled() {
        expectPost("/api/channel/bindings/b1/tw-enabled", "{\"enabled\":true}");
        service.toggleTwEnabled("b1", true);
    }

    @Test
    void saveSteamToken() {
        expectPost("/api/channels/enimaloc/settings/steam-personal-token", "{\"token\":\"KEY\",\"shared\":true}");
        service.saveSteamToken("enimaloc", "KEY", true);
    }

    @Test
    void steamTokenSharing() {
        expectPost("/api/channels/enimaloc/settings/steam-personal-token/sharing", "{\"shared\":false}");
        service.steamTokenSharing("enimaloc", false);
    }

    @Test
    void deleteSteamToken() {
        expectPost("/api/channels/enimaloc/settings/steam-personal-token/delete");
        service.deleteSteamToken("enimaloc");
    }

    @Test
    void refreshSteamProfileCache() {
        expectPost("/api/channels/enimaloc/steam/refresh-profile-cache");
        service.refreshSteamProfileCache("enimaloc");
    }

    @Test
    void minecraftEnroll() {
        expectPost("/api/connect/minecraft", "{\"name\":\"Steve\"}");
        service.minecraftEnroll("enimaloc", "Steve");
    }

    @Test
    void saveObsSettings() {
        expectPost("/api/channels/enimaloc/settings/obs",
                "{\"enabled\":true,\"host\":\"10.0.0.2\",\"port\":4456,\"password\":\"pw\"}");
        service.saveObsSettings("enimaloc", true, "10.0.0.2", 4456, "pw");
    }

    @Test
    void minecraftSync() {
        expectPost("/api/connect/minecraft/sync");
        service.minecraftSync("enimaloc");
    }

    @Test
    void minecraftDisconnect() {
        expect(HttpMethod.DELETE, "/api/connect/minecraft").andRespond(withNoContent());
        service.minecraftDisconnect("enimaloc");
    }

    @Test
    void saveCclSettings() {
        expectPost("/api/channels/enimaloc/settings/ccl", "{\"cclEnabled\":true,\"blockedCcls\":[\"Gore\"]}");
        service.saveCclSettings("enimaloc", true, Set.of("Gore"));
    }

    @Test
    void saveTwSettings() {
        expectPost("/api/channels/enimaloc/settings/tws", "{\"enabled\":false,\"blockedTws\":[]}");
        service.saveTwSettings("enimaloc", false, Set.of());
    }

    @Test
    void saveNoGameSettings() {
        expectPost("/api/channels/enimaloc/settings/no-game", """
                {"twitchGameId":"1","twitchGameName":"Just Chatting","ccls":[],
                 "applyOnStreamStart":true,"applyOnNoGame":false,"applyOnStreamEnd":true}""");
        service.saveNoGameSettings("enimaloc", "1", "Just Chatting", Set.of(), true, false, true);
    }

    @Test
    void saveIncompleteFallbackSettings() {
        expectPost("/api/channels/enimaloc/settings/incomplete-fallback",
                "{\"twitchGameId\":null,\"twitchGameName\":null,\"ccls\":[\"Drugs\"]}");
        service.saveIncompleteFallbackSettings("enimaloc", null, null, Set.of("Drugs"));
    }

    @Test
    void dtddValidate() {
        expectPost("/api/channel/dtdd-mapping/validate", "{\"igdbId\":\"42\"}");
        service.dtddValidate("42");
    }

    @Test
    void dtddPropose() {
        expectPost("/api/channel/dtdd-mapping/propose", "{\"igdbId\":\"42\",\"dtddId\":9,\"reason\":\"correction\"}");
        service.dtddPropose("42", 9L, "correction");
    }
}
