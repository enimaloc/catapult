package fr.enimaloc.catapult.service.real;

import java.util.Map;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
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
import java.util.UUID;

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
        respond(HttpMethod.GET, "/api/channels", """
                {"viewerTwitchId":"1","channels":[{"id":"00000000-0000-0000-0000-000000000001","twitchId":"1",
                 "twitchUsername":"enimaloc","profileImageUrl":"img","live":true}]}""");

        assertThat(service.channelList()).isEqualTo(new ChannelListResponse("1", List.of(new ChannelDto(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "1", "enimaloc", "img", true))));
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
        respond(HttpMethod.GET, "/api/me/obs", "{\"obsHost\":\"10.0.0.2\",\"obsPort\":4456,\"obsPassword\":\"pw\",\"twitchatBranch\":\"auto\"}");

        assertThat(service.obsConnection()).isEqualTo(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "auto"));
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
    void currentGame() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/status",
                "{\"channelUsername\":\"enimaloc\",\"isOwner\":true,\"botEnabled\":true,\"isLive\":false,\"currentGame\":{\"bindingId\":\"b1\",\"sourceName\":\"Halo\",\"sourceType\":\"XBOX\"}}");

        assertThat(service.currentGame("enimaloc")).isEqualTo(new GameDto("b1", "Halo", "XBOX"));
    }

    @Test
    void currentGame_none() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/status", "{\"channelUsername\":\"enimaloc\",\"isOwner\":true,\"botEnabled\":true,\"isLive\":false,\"currentGame\":null}");

        assertThat(service.currentGame("enimaloc")).isNull();
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
    void saveTwitchatBranch() {
        expectPost("/api/channels/enimaloc/settings/twitchat/branch", "{\"branch\":\"beta\"}");
        service.saveTwitchatBranch("enimaloc", "beta");
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

    @Test
    void twitchatEvents_readsTheOwnersLists() {
        expect(HttpMethod.GET, "/api/channels/streamer/twitchat/events").andRespond(withSuccess(
                "{\"STREAM_STARTED\":[{\"action\":\"SEND_MESSAGE\",\"mode\":\"FORM\",\"dataJson\":\"{}\",\"enabled\":true}]}",
                MediaType.APPLICATION_JSON));

        assertThat(service.twitchatEvents("streamer").get("STREAM_STARTED")).singleElement()
                .extracting(TwitchatEventActionDto::action).isEqualTo("SEND_MESSAGE");
    }

    @Test
    void saveTwitchatEvent_putsTheList_andReturnsNoErrorOn204() {
        expect(HttpMethod.PUT, "/api/channels/streamer/twitchat/events/STREAM_STARTED/actions")
                .andExpect(content().json("[{\"action\":\"SEND_MESSAGE\",\"mode\":\"RAW\",\"dataJson\":\"{}\",\"enabled\":false}]"))
                .andRespond(withNoContent());

        assertThat(service.saveTwitchatEvent("streamer", "STREAM_STARTED",
                List.of(new TwitchatEventActionDto("SEND_MESSAGE", "RAW", "{}", false)))).isEmpty();
    }

    @Test
    void saveTwitchatEvent_relaysTheValidationErrors() {
        expect(HttpMethod.PUT, "/api/channels/streamer/twitchat/events/STREAM_STARTED/actions")
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"index\":0,\"field\":\"message\",\"message\":\"Required\"}]"));

        assertThat(service.saveTwitchatEvent("streamer", "STREAM_STARTED", List.of()))
                .containsExactly(new TwitchatValidationError(0, "message", "Required"));
    }

    @Test
    void executeTwitchatAction_isTrueOnlyWhenExecuted() {
        expect(HttpMethod.POST, "/api/twitchat/actions/tok").andRespond(withSuccess("{\"result\":\"EXECUTED\"}", MediaType.APPLICATION_JSON));
        assertThat(service.executeTwitchatAction("tok")).isTrue();
    }

    // --- admin: members ------------------------------------------------------------------------

    @Test
    void adminMembers_readsTheAccountsLeavingTheirSecretsOut() {
        respond(HttpMethod.GET, "/api/admin/members", """
                {"members":[{"id":"00000000-0000-0000-0000-000000000001","twitchId":"1","twitchUsername":"enimaloc",
                  "profileImageUrl":"img","steamId":"765","steamPersonalToken":"secret","systemAccount":false,
                  "botEnabled":true,"status":"ACTIVE","createdAt":"2025-03-01T12:00:00Z"}],
                 "liveStatus":{"00000000-0000-0000-0000-000000000001":true},"isMockProfile":false}""");

        var page = service.adminMembers();

        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(page.members()).containsExactly(new fr.enimaloc.catapult.common.dto.admin.AdminMemberDto(
                id, "1", "enimaloc", "img", "765", false, true, "ACTIVE", java.time.Instant.parse("2025-03-01T12:00:00Z")));
        assertThat(page.isLive(id)).isTrue();
    }

    @Test
    void adminMemberActions() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String base = "/api/admin/members/" + id;
        expectPost(base + "/bot/toggle");
        expectPost(base + "/steam/unlink");
        expectPost(base + "/twitch/unlink");
        expectPost(base + "/promote-to-system");
        expectPost(base + "/delete");
        expectPost(base + "/migrate", """
                {"targetId":"00000000-0000-0000-0000-000000000002","migrateSettings":true,"migrateGetters":false,"migrateBindings":true}""");

        service.adminToggleMemberBot(id);
        service.adminUnlinkMemberSteam(id);
        service.adminUnlinkMemberTwitch(id);
        service.adminPromoteMemberToSystem(id);
        service.adminDeleteMember(id);
        service.adminMigrateMember(id, UUID.fromString("00000000-0000-0000-0000-000000000002"), true, false, true);
    }

    // --- admin: CCL, caches, data --------------------------------------------------------------

    @Test
    void adminCcl_readsTheCclsWithTheirDescriptors() {
        respond(HttpMethod.GET, "/api/admin/ccl", """
                {"ccls":[{"id":"Gambling","name":"Gambling","description":"d","mappedDescriptions":["Simulated Gambling"],
                  "igdbMappings":[{"id":6,"description":"Simulated Gambling","organizationId":1,"displayName":"ESRB — Simulated Gambling"},
                                  {"id":2,"description":"Blood","organizationId":1,"displayName":"ESRB — Blood"}]}],
                 "igdbDescriptors":[{"id":6,"description":"Simulated Gambling","organizationId":1,"displayName":"ESRB — Simulated Gambling"}]}""");

        var page = service.adminCcl();

        assertThat(page.ccls()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("Gambling");
            assertThat(c.mappedIds()).isEqualTo("2,6");
        });
        assertThat(page.igdbDescriptors()).extracting(d -> d.label()).containsExactly("ESRB — Simulated Gambling");
    }

    @Test
    void adminCclActions() {
        expectPost("/api/admin/ccl/refresh");
        expectPost("/api/admin/ccl/Gambling/mappings", "{\"igdbCategoryIds\":[6]}");

        assertThat(service.adminRefreshCcl()).isEqualTo(204);
        assertThat(service.adminSaveCclMappings("Gambling", Set.of(6L))).isEqualTo(204);
    }

    @Test
    void adminCclActions_relayAFailedStatus() {
        expect(HttpMethod.POST, "/api/admin/ccl/refresh").andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expect(HttpMethod.POST, "/api/admin/ccl/Gambling/mappings").andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(service.adminRefreshCcl()).isEqualTo(503);
        assertThat(service.adminSaveCclMappings("Gambling", Set.of(6L))).isEqualTo(403);
    }

    @Test
    void adminCaches_readsTheListAPageAndAnEntry() {
        respond(HttpMethod.GET, "/api/admin/caches", "[{\"name\":\"igdb-game-cache\",\"size\":30,\"deletable\":true}]");
        respond(HttpMethod.GET, "/api/admin/caches/igdb-game-cache?q=doom&page=1", """
                {"content":[{"key":"1001","value":"Doom"}],"number":1,"size":25,"totalPages":2,
                 "totalElements":26,"first":false,"last":true,"empty":false}""");
        respond(HttpMethod.GET, "/api/admin/caches/igdb-game-cache/entry?key=a%20b",
                "{\"key\":\"a b\",\"detail\":{\"name\":\"Doom\"},\"expiresInSeconds\":60}");

        assertThat(service.adminCaches()).containsExactly(new CacheSummaryDto("igdb-game-cache", 30, true));
        var entries = service.adminCacheEntries("igdb-game-cache", "doom", 1);
        assertThat(entries.content()).containsExactly(new CacheEntryDto("1001", "Doom"));
        assertThat(entries.last()).isTrue();
        var entry = service.adminCacheEntry("igdb-game-cache", "a b");
        assertThat(entry.detail()).isEqualTo(Map.of("name", "Doom"));
        assertThat(entry.expiresInSeconds()).isEqualTo(60L);
    }

    @Test
    void adminCacheEntries_sendsABlankSearchWhenThereIsNone() {
        respond(HttpMethod.GET, "/api/admin/caches/igdb-game-cache?q=&page=0",
                "{\"content\":[],\"number\":0,\"totalPages\":0,\"totalElements\":0}");

        assertThat(service.adminCacheEntries("igdb-game-cache", null, 0).content()).isEmpty();
    }

    @Test
    void adminDeleteCacheEntry_relaysTheStatus() {
        expect(HttpMethod.DELETE, "/api/admin/caches/igdb-game-cache?key=1001").andRespond(withNoContent());
        expect(HttpMethod.DELETE, "/api/admin/caches/tw-known-paths?key=x").andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(service.adminDeleteCacheEntry("igdb-game-cache", "1001")).isEqualTo(204);
        assertThat(service.adminDeleteCacheEntry("tw-known-paths", "x")).isEqualTo(400);
    }

    @Test
    void adminData_readsAndWritesRows_relayingTheStatuses() {
        respond(HttpMethod.GET, "/api/admin/data", "[{\"name\":\"chat-command\",\"size\":3}]");
        respond(HttpMethod.GET, "/api/admin/data/chat-command?q=&page=0",
                "{\"content\":[{\"id\":\"1\",\"name\":\"!lurk\"}],\"number\":0,\"totalPages\":1,\"totalElements\":1}");
        respond(HttpMethod.GET, "/api/admin/data/chat-command/1", "{\"id\":\"1\",\"name\":\"!lurk\",\"aliases\":[]}");
        expect(HttpMethod.POST, "/api/admin/data/chat-command").andExpect(content().json("{\"name\":\"!hi\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"4\"}"));
        expect(HttpMethod.PUT, "/api/admin/data/chat-command/1").andExpect(content().json("{\"name\":\"!hey\"}"))
                .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));
        expect(HttpMethod.DELETE, "/api/admin/data/chat-command/1").andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(service.adminDataRepos()).containsExactly(new RepoSummaryDto("chat-command", 3));
        assertThat(service.adminDataRows("chat-command", null, 0).content()).containsExactly(Map.of("id", "1", "name", "!lurk"));
        assertThat(service.adminDataRow("chat-command", "1")).containsEntry("aliases", List.of());
        assertThat(service.adminCreateDataRow("chat-command", Map.of("name", "!hi"))).isEqualTo(201);
        assertThat(service.adminUpdateDataRow("chat-command", "1", Map.of("name", "!hey"))).isEqualTo(200);
        assertThat(service.adminDeleteDataRow("chat-command", "1")).isEqualTo(409);
    }

    @Test
    void adminDataRow_keepsAnEncodedIdAsOnePathSegment() {
        // IdCodec ids are already percent-encoded: catapult-api decodes the segment once, IdCodec once more.
        respond(HttpMethod.GET, "/api/admin/data/twitch-ccl/a~b%2520c", "{\"id\":\"a~b%20c\"}");

        assertThat(service.adminDataRow("twitch-ccl", "a~b%20c")).containsEntry("id", "a~b%20c");
    }

    @Test
    void wellKnown_mapsTheEnvelope_withoutTheBearer() {
        expect(HttpMethod.GET, "/api/well-known/serve?location=WELL_KNOWN&path=change-password")
                .andRespond(withSuccess("{\"status\":302,\"contentType\":\"text/plain\",\"headers\":"
                        + "[{\"name\":\"Location\",\"value\":\"/x\"}],\"content\":\"\"}", MediaType.APPLICATION_JSON));

        var content = service.wellKnown(fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "change-password");

        assertThat(content).isEqualTo(new fr.enimaloc.catapult.service.WellKnownContent(200,
                new fr.enimaloc.catapult.common.dto.admin.WellKnownResponseDto(302, "text/plain",
                        java.util.List.of(new fr.enimaloc.catapult.common.dto.admin.WellKnownHeader("Location", "/x")), "")));
    }

    @Test
    void adminWellKnownSchema_readsTheSchemaText() {
        respond(HttpMethod.GET, "/api/admin/well-known/schema?path=gpc.json", "{\"type\":\"object\"}");

        assertThat(service.adminWellKnownSchema("gpc.json")).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void wellKnown_relaysA404() {
        expect(HttpMethod.GET, "/api/well-known/serve?location=ROOT&path=nope.txt").andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(service.wellKnown(fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT, "nope.txt").status())
                .isEqualTo(404);
    }

    @Test
    void adminWellKnown_writesAndReads() {
        var request = new fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest(
                fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.ROOT, "riot.txt", 200, java.util.List.of(), "text/plain", "code");
        String json = "{\"location\":\"ROOT\",\"path\":\"riot.txt\",\"status\":200,\"headers\":[],\"contentType\":\"text/plain\",\"content\":\"code\"}";
        expect(HttpMethod.POST, "/api/admin/well-known").andExpect(content().json(json))
                .andRespond(withStatus(HttpStatus.CREATED));
        expect(HttpMethod.PUT, "/api/admin/well-known/3").andExpect(content().json(json)).andRespond(withSuccess());
        expect(HttpMethod.DELETE, "/api/admin/well-known/3").andRespond(withNoContent());
        respond(HttpMethod.GET, "/api/admin/well-known", "[{\"id\":3,\"location\":\"ROOT\",\"path\":\"riot.txt\","
                + "\"status\":200,\"contentType\":\"text/plain\",\"size\":4,\"updatedAt\":\"2026-10-16T00:00:00Z\"}]");
        respond(HttpMethod.GET, "/api/admin/well-known/3", "{\"id\":3,\"location\":\"ROOT\",\"path\":\"riot.txt\","
                + "\"status\":200,\"headers\":[],\"contentType\":\"text/plain\",\"content\":\"code\",\"updatedAt\":\"2026-10-16T00:00:00Z\"}");
        respond(HttpMethod.GET, "/api/admin/well-known/iana", "{\"entries\":[{\"suffix\":\"security.txt\","
                + "\"reference\":\"RFC 9116\",\"status\":\"permanent\",\"changeController\":\"IETF\"}],\"source\":\"LIVE\"}");

        assertThat(service.adminCreateWellKnownFile(request)).isEqualTo(201);
        assertThat(service.adminUpdateWellKnownFile(3, request)).isEqualTo(200);
        assertThat(service.adminDeleteWellKnownFile(3)).isEqualTo(204);
        assertThat(service.adminWellKnownFiles()).extracting("path").containsExactly("riot.txt");
        assertThat(service.adminWellKnownFile(3).content()).isEqualTo("code");
        assertThat(service.adminIanaRegistry().entries()).hasSize(1);
    }

    @Test
    void wellKnown_sendsNoAuthorizationEvenWithASessionJwt() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "expired.jwt.token");
        request.setSession(session);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            expect(HttpMethod.GET, "/api/well-known/serve?location=WELL_KNOWN&path=a.txt")
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                            .headerDoesNotExist("Authorization"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                            .headerDoesNotExist("X-Catapult-Anonymous"))
                    .andRespond(withSuccess("{\"status\":200,\"contentType\":\"text/plain\",\"headers\":[],\"content\":\"x\"}", MediaType.APPLICATION_JSON));
            service.wellKnown(fr.enimaloc.catapult.common.dto.admin.WellKnownLocation.WELL_KNOWN, "a.txt");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    // --- notifications and broadcasts ---------------------------------------------------------

    private static final String NOTIF = "{\"id\":\"00000000-0000-0000-0000-000000000009\",\"title\":\"t\",\"bodyHtml\":\"<p>b</p>\","
            + "\"severity\":\"INFO\",\"ctaUrl\":null,\"ctaLabel\":null,\"expiresAt\":null,"
            + "\"createdAt\":\"2026-10-18T10:00:00Z\",\"read\":false}";
    private static final UUID NOTIF_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");

    @Test
    void notificationSnapshot_readsTheSnapshot() {
        respond(HttpMethod.GET, "/api/notifications/snapshot", "{\"items\":[" + NOTIF + "],\"unreadCount\":4}");

        var snapshot = service.notificationSnapshot();

        assertThat(snapshot.unreadCount()).isEqualTo(4);
        assertThat(snapshot.items()).extracting("id").containsExactly(NOTIF_ID);
    }

    @Test
    void notifications_mapsASpringPage() {
        // No sort parameter: catapult-api's repository already orders newest first and would reject
        // a Pageable sort on a property NotificationRecipient does not have.
        respond(HttpMethod.GET, "/api/notifications?page=2&size=20", "{\"content\":[" + NOTIF + "],"
                + "\"number\":2,\"totalPages\":3,\"totalElements\":41,\"size\":20,\"pageable\":{\"pageNumber\":2,\"paged\":true},"
                + "\"first\":false,\"last\":true}");

        var page = service.notifications(2);

        assertThat(page.number()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.content()).hasSize(1);
    }

    @Test
    void markNotificationRead_andReadAll_returnTheStatus() {
        expect(HttpMethod.POST, "/api/notifications/" + NOTIF_ID + "/read").andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/notifications/read-all").andRespond(withNoContent());

        assertThat(service.markNotificationRead(NOTIF_ID)).isEqualTo(204);
        assertThat(service.markAllNotificationsRead()).isEqualTo(204);
    }

    @Test
    void adminNotifications_readsAPageOfAdminDtos() {
        respond(HttpMethod.GET, "/api/admin/notifications?page=0&size=20", "{\"content\":[{\"id\":\"" + NOTIF_ID
                + "\",\"title\":\"t\",\"body\":\"b\",\"bodyHtml\":\"<p>b</p>\",\"severity\":\"WARNING\",\"audience\":\"TARGETED\","
                + "\"targetUserId\":\"" + NOTIF_ID + "\",\"targetLogin\":\"alice\","
                + "\"createdAt\":\"2026-10-18T10:00:00Z\"}],\"number\":0,\"totalPages\":1,\"totalElements\":1}");

        var page = service.adminNotifications(0);

        assertThat(page.content()).extracting("body").containsExactly("b");
        assertThat(page.content().get(0).audience()).isEqualTo("TARGETED");
        assertThat(page.content().get(0).targetLogin()).isEqualTo("alice");
    }

    @Test
    void adminCreateNotification_postsTheBody() {
        var body = new fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody("t", "b",
                fr.enimaloc.catapult.common.dto.notification.Severity.INFO, null, null, null, null);
        expect(HttpMethod.POST, "/api/admin/notifications").andExpect(content().json("{\"title\":\"t\",\"body\":\"b\",\"severity\":\"INFO\"}"))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(service.adminCreateNotification(body)).isEqualTo(201);
    }

    @Test
    void adminDeleteNotification_returnsTheStatus() {
        expect(HttpMethod.DELETE, "/api/admin/notifications/" + NOTIF_ID).andRespond(withNoContent());

        assertThat(service.adminDeleteNotification(NOTIF_ID)).isEqualTo(204);
    }

    @Test
    void adminPreviewNotification_returnsTheBodyHtml() {
        expect(HttpMethod.POST, "/api/admin/notifications/preview").andExpect(content().json("{\"body\":\"**a**\"}"))
                .andRespond(withSuccess("{\"bodyHtml\":\"<p><strong>a</strong></p>\"}", MediaType.APPLICATION_JSON));

        assertThat(service.adminPreviewNotification("**a**")).isEqualTo("<p><strong>a</strong></p>");
    }

    @Test
    void adminPreviewNotification_isNullWhenRefused() {
        expect(HttpMethod.POST, "/api/admin/notifications/preview").andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(service.adminPreviewNotification("")).isNull();
    }

    @Test
    void adminBroadcast_sendsTheNameDiscriminator_andRelaysA429() {
        expect(HttpMethod.POST, "/api/admin/broadcast")
                .andExpect(content().json("{\"name\":\"alert.info\",\"title\":\"t\",\"body\":\"b\",\"ttlSeconds\":0}"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(service.adminBroadcast(new fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto.AlertInfo("t", "b", 0)))
                .isEqualTo(429);
    }
}
