package fr.enimaloc.catapult.service.real;

import java.util.Map;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
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
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
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

    @Test
    void steamConnectUrl_isWhereCatapultApiSendsTheMember_andNeverAskedLoggedOut() {
        assertThat(service.steamConnectUrl()).isNull();

        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, "jwt");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            respond(HttpMethod.GET, "/api/connect/steam/start", "{\"redirectUrl\":\"https://steamcommunity.com/openid/login?x\"}");
            assertThat(service.steamConnectUrl()).isEqualTo("https://steamcommunity.com/openid/login?x");
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void steamDisconnect_postsAndRelaysTheStatus() {
        expectPost("/api/connect/steam/disconnect");
        assertThat(service.steamDisconnect()).isEqualTo(204);
    }

    @Test
    void changelog_readsTheVersions_orNullWhenItFails() {
        respond(HttpMethod.GET, "/api/changelog", "[{\"version\":\"0.3.7\",\"entries\":[]}]");
        expect(HttpMethod.GET, "/api/changelog").andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(service.changelog()).singleElement().satisfies(s -> assertThat(s.version()).isEqualTo("0.3.7"));
        assertThat(service.changelog()).isNull();
    }

    @Test
    void apiAvailable_onlyWhenItsHealthIsUp() {
        respond(HttpMethod.GET, "/api/health", "{\"status\":\"UP\"}");
        respond(HttpMethod.GET, "/api/health", "{\"status\":\"DOWN\"}");
        expect(HttpMethod.GET, "/api/health").andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(service.apiAvailable()).isTrue();
        assertThat(service.apiAvailable()).isFalse();
        assertThat(service.apiAvailable()).isFalse();
    }

    @Test
    void invitePage_readsTheCodeLinkAndRedemptions() {
        respond(HttpMethod.GET, "/api/invite", """
                {"canInvite":true,"inviteId":"00000000-0000-0000-0000-000000000001","code":"ABC234",
                 "inviteUrl":"https://catapult.test/join?invite=ABC234","regeneratedAt":"2026-10-01T10:00:00Z",
                 "redemptions":[{"inviteeTwitchId":"42","redeemedAt":"2026-10-02T10:00:00Z"}]}""");

        var page = service.invitePage();

        assertThat(page.canInvite()).isTrue();
        assertThat(page.code()).isEqualTo("ABC234");
        assertThat(page.inviteUrl()).isEqualTo("https://catapult.test/join?invite=ABC234");
        assertThat(page.redemptions()).singleElement().satisfies(r -> assertThat(r.inviteeTwitchId()).isEqualTo("42"));
    }

    @Test
    void invitePage_isNullWhenItFails() {
        expect(HttpMethod.GET, "/api/invite").andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(service.invitePage()).isNull();
    }

    @Test
    void regenerateInvite_posts() {
        expectPost("/api/invite/regenerate");

        assertThat(service.regenerateInvite()).isEqualTo(204);
    }

    @Test
    void deleteAccount_postsTheConfirmation() {
        expectPost("/api/channels/me/settings/delete-account", "{\"confirmUsername\":\"Me\"}");

        assertThat(service.deleteAccount("me", "Me")).isEqualTo(204);
    }

    @Test
    void cancelAccountDeletion_posts() {
        expectPost("/api/channels/me/settings/cancel-deletion");

        assertThat(service.cancelAccountDeletion("me")).isEqualTo(204);
    }

    @Test
    void accountInfo_readsTheValidatedAccount() {
        respond(HttpMethod.GET, "/api/auth/validate", """
                {"id":"00000000-0000-0000-0000-000000000001","twitchId":"1","username":"me","status":"PENDING_DELETION",
                 "roles":[],"deletionRequestedAt":"2026-10-01T10:00:00Z"}""");

        var info = service.accountInfo();

        assertThat(info.status()).isEqualTo("PENDING_DELETION");
        assertThat(info.deletionRequestedAt()).isEqualTo("2026-10-01T10:00:00Z");
    }

    @Test
    void accountInfo_isNullWhenItFails() {
        expect(HttpMethod.GET, "/api/auth/validate").andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(service.accountInfo()).isNull();
    }

    @Test
    void accountDeletionDelayDays_readsTheConfig() {
        respond(HttpMethod.GET, "/api/config/app", "{\"name\":\"Catapult\",\"deletionDelayDays\":14,\"gettersName\":[]}");

        assertThat(service.accountDeletionDelayDays()).isEqualTo(14);
    }

    @Test
    void accountDeletionDelayDays_fallsBackTo7() {
        expect(HttpMethod.GET, "/api/config/app").andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(service.accountDeletionDelayDays()).isEqualTo(7);
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
    void widgetObsConfig_isTheWidgetsObsConnection_onBranchAuto_askedAnonymously() {
        UUID token = UUID.fromString("11111111-2222-3333-4444-555555555555");
        expect(HttpMethod.GET, "/api/twitchat/widget/" + token)
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"obsHost\":\"10.0.0.2\",\"obsPort\":4456,\"obsPassword\":\"pw\"}", MediaType.APPLICATION_JSON));

        assertThat(service.widgetObsConfig(token)).isEqualTo(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "auto"));
    }

    @Test
    void widgetObsConfig_isOnTheOwnersTwitchatBranch() {
        UUID token = UUID.fromString("11111111-2222-3333-4444-555555555555");
        expect(HttpMethod.GET, "/api/twitchat/widget/" + token)
                .andRespond(withSuccess("{\"obsHost\":\"10.0.0.2\",\"obsPort\":4456,\"obsPassword\":\"pw\",\"twitchatBranch\":\"beta\"}", MediaType.APPLICATION_JSON));

        assertThat(service.widgetObsConfig(token)).isEqualTo(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "beta"));
    }

    @Test
    void help_forwardsTheLocale() {
        expect(HttpMethod.GET, "/api/help/status")
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Accept-Language", "fr-FR"))
                .andRespond(withSuccess("{\"title\":\"Statut\",\"body\":\"<p>x</p>\"}", MediaType.APPLICATION_JSON));

        assertThat(service.help("status", java.util.Locale.FRANCE))
                .isEqualTo(new fr.enimaloc.catapult.common.dto.HelpContent("Statut", "<p>x</p>"));
    }

    @Test
    void help_isNullWhenUnreachable() {
        expect(HttpMethod.GET, "/api/help/status").andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(service.help("status", java.util.Locale.ENGLISH)).isNull();
    }

    @Test
    void widgetObsConfig_isNullWhenUnknownDisabledOrUnreachable() {
        UUID token = UUID.randomUUID();
        expect(HttpMethod.GET, "/api/twitchat/widget/" + token).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(service.widgetObsConfig(token)).isNull();

        api.reset();
        expect(HttpMethod.GET, "/api/twitchat/widget/" + token).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(service.widgetObsConfig(token)).isNull();
    }

    @Test
    void widgetAccess_readsTheOwnerAndWhetherItIsOn() {
        UUID token = UUID.fromString("11111111-2222-3333-4444-555555555555");
        expect(HttpMethod.GET, "/api/twitchat/widget/" + token + "/access")
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"ownerId\":\"99999999-8888-7777-6666-555555555555\",\"enabled\":true}", MediaType.APPLICATION_JSON));

        assertThat(service.widgetAccess(token)).isEqualTo(new ApiClient.Result<>(200, new TwitchatWidgetAccessResponse(
                "99999999-8888-7777-6666-555555555555", true)));
    }

    @Test
    void widgetAccess_tellsUnknownFromUnreachable() {
        UUID token = UUID.randomUUID();
        expect(HttpMethod.GET, "/api/twitchat/widget/" + token + "/access").andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(service.widgetAccess(token)).isEqualTo(new ApiClient.Result<>(404, null));

        api.reset();
        expect(HttpMethod.GET, "/api/twitchat/widget/" + token + "/access").andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(service.widgetAccess(token)).isEqualTo(new ApiClient.Result<>(502, null));
    }

    @Test
    void channelSettings() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/settings", """
                {"cclFeatureEnabled":true,"blockedCcls":[],"noGameCcls":[],"applyDefaultOnStreamStart":false,
                 "applyDefaultOnNoGame":false,"applyDefaultOnStreamEnd":false,"incompleteFallbackCcls":[],
                 "availableCcls":[],"twFeatureEnabled":false,"blockedTws":[],"availableTws":[],
                 "raidRestoreGameMinutes":15,"raidRestoreGameAvailable":true}""");

        assertThat(service.channelSettings("enimaloc")).isInstanceOf(UserSettingsDto.class)
                .extracting(UserSettingsDto::cclFeatureEnabled, UserSettingsDto::raidRestoreGameMinutes,
                        UserSettingsDto::raidRestoreGameAvailable)
                .containsExactly(true, 15, true);
    }

    @Test
    void currentBinding_isTheGamePlayedNows_nullOnNoContent() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/bindings/current", """
                {"id":"b1","status":"AUTO","sourceType":"STEAM","sourceName":"Valheim","twitchGameId":"508455",
                 "twitchGameName":"Valheim","ignored":false,"cclEnabled":true,"ccls":["a"],"twEnabled":false,
                 "twOverride":false,"tws":[]}""");
        assertThat(service.currentBinding("enimaloc")).extracting(BindingDto::id).isEqualTo("b1");

        api.reset();
        expect(HttpMethod.GET, "/api/channels/enimaloc/bindings/current").andRespond(withNoContent());
        assertThat(service.currentBinding("enimaloc")).isNull();
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
    void twitchatSettings_readsTheWidgetToken() {
        respond(HttpMethod.GET, "/api/channels/enimaloc/settings/twitchat",
                "{\"enabled\":true,\"obsHost\":\"h\",\"obsPort\":4455,\"hasPassword\":true,\"widgetToken\":\"11111111-2222-3333-4444-555555555555\"}");

        assertThat(service.twitchatSettings("enimaloc")).isEqualTo(new fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse(
                true, "h", 4455, true, "11111111-2222-3333-4444-555555555555"));
    }

    @Test
    void regenerateTwitchatWidget_postsAndReadsTheNewSettings() {
        expect(HttpMethod.POST, "/api/channels/enimaloc/settings/twitchat/regenerate").andExpect(content().string(""))
                .andRespond(withSuccess("{\"enabled\":true,\"obsHost\":\"h\",\"obsPort\":4455,\"hasPassword\":true,\"widgetToken\":\"aaaaaaaa-2222-3333-4444-555555555555\"}",
                        MediaType.APPLICATION_JSON));

        var result = service.regenerateTwitchatWidget("enimaloc");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().widgetToken()).isEqualTo("aaaaaaaa-2222-3333-4444-555555555555");
    }

    @Test
    void regenerateTwitchatWidget_relaysTheRefusalsStatus_and502WhenUnreachable() {
        expect(HttpMethod.POST, "/api/channels/enimaloc/settings/twitchat/regenerate").andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(service.regenerateTwitchatWidget("enimaloc")).isEqualTo(new fr.enimaloc.catapult.service.http.ApiClient.Result<>(403, null));

        api.reset();
        expect(HttpMethod.POST, "/api/channels/enimaloc/settings/twitchat/regenerate").andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(service.regenerateTwitchatWidget("enimaloc").status()).isEqualTo(502);
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
        respond(HttpMethod.GET, "/api/admin/members?page=2&q=enim", """
                {"members":[{"id":"00000000-0000-0000-0000-000000000001","twitchId":"1","twitchUsername":"enimaloc",
                  "profileImageUrl":"img","steamId":"765","steamPersonalToken":"secret","systemAccount":false,
                  "botEnabled":true,"status":"ACTIVE","createdAt":"2025-03-01T12:00:00Z"}],
                 "liveStatus":{"00000000-0000-0000-0000-000000000001":true},"isMockProfile":false,
                 "number":2,"totalPages":3,"totalElements":101}""");

        var page = service.adminMembers("enim", 2);

        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(page.members()).containsExactly(new fr.enimaloc.catapult.common.dto.admin.AdminMemberDto(
                id, "1", "enimaloc", "img", "765", false, true, "ACTIVE", java.time.Instant.parse("2025-03-01T12:00:00Z")));
        assertThat(page.isLive(id)).isTrue();
        assertThat(page.number()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.totalElements()).isEqualTo(101);
    }

    @Test
    void adminMembers_withoutASearch_asksForThePageAlone() {
        respond(HttpMethod.GET, "/api/admin/members?page=0&q=", "{\"members\":[],\"liveStatus\":{},\"isMockProfile\":false,\"number\":0,\"totalPages\":0,\"totalElements\":0}");

        assertThat(service.adminMembers(null, 0).members()).isEmpty();
    }

    @Test
    void adminSearchMembers_readsTheMatchesAndEscapesTheQuery() {
        respond(HttpMethod.GET, "/api/admin/members/search?q=a%26b%20c",
                "[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"twitchId\":\"1\",\"twitchUsername\":\"enimaloc\","
                        + "\"systemAccount\":false,\"botEnabled\":true,\"status\":\"ACTIVE\"}]");

        assertThat(service.adminSearchMembers("a&b c")).extracting(m -> m.twitchUsername()).containsExactly("enimaloc");
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
    void metaTags_areReadWithoutTheBearerEvenWithASessionJwt_andNullWhenCatapultApiFails() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(ApiClient.SESSION_JWT_KEY, "expired.jwt.token");
        request.setSession(session);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            expect(HttpMethod.GET, "/api/meta-tags")
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Authorization"))
                    .andRespond(withSuccess("[{\"id\":1,\"attribute\":\"PROPERTY\",\"key\":\"og:image\",\"content\":\"https://x/y.png\","
                            + "\"updatedAt\":\"2026-10-19T10:00:00Z\"}]", MediaType.APPLICATION_JSON));
            expect(HttpMethod.GET, "/api/meta-tags").andRespond(withStatus(HttpStatus.BAD_GATEWAY));

            assertThat(service.metaTags()).containsExactly(new fr.enimaloc.catapult.common.dto.admin.MetaTagDto(1,
                    fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY, "og:image", "https://x/y.png",
                    java.time.Instant.parse("2026-10-19T10:00:00Z")));
            assertThat(service.metaTags()).isNull();
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void adminMetaTags_areWrittenWithTheirStatus() {
        var request = new fr.enimaloc.catapult.common.dto.admin.MetaTagRequest(
                fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.NAME, "theme-color", "#000");
        String json = "{\"attribute\":\"NAME\",\"key\":\"theme-color\",\"content\":\"#000\"}";
        expect(HttpMethod.POST, "/api/admin/meta-tags").andExpect(content().json(json)).andRespond(withStatus(HttpStatus.CREATED));
        expect(HttpMethod.PUT, "/api/admin/meta-tags/3").andExpect(content().json(json)).andRespond(withStatus(HttpStatus.CONFLICT));
        expect(HttpMethod.DELETE, "/api/admin/meta-tags/3").andRespond(withNoContent());

        assertThat(service.adminCreateMetaTag(request)).isEqualTo(201);
        assertThat(service.adminUpdateMetaTag(3, request)).isEqualTo(409);
        assertThat(service.adminDeleteMetaTag(3)).isEqualTo(204);
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
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

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
                + "\"targetUserId\":\"" + NOTIF_ID + "\",\"targetLogin\":\"alice\",\"recipientCount\":1,"
                + "\"createdAt\":\"2026-10-18T10:00:00Z\"}],\"number\":0,\"totalPages\":1,\"totalElements\":1}");

        var page = service.adminNotifications(0);

        assertThat(page.content()).extracting("body").containsExactly("b");
        assertThat(page.content().get(0).audience()).isEqualTo("TARGETED");
        assertThat(page.content().get(0).targetLogin()).isEqualTo("alice");
        assertThat(page.content().get(0).recipientCount()).isEqualTo(1);
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
    void adminSendNotification_readsHowManyWereTold_andPassesTheExperimentIds() {
        UUID experiment = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        var body = new fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody("t", "b",
                fr.enimaloc.catapult.common.dto.notification.Severity.INFO, null, null, null, null, "EXPERIMENT", experiment, variant);
        expect(HttpMethod.POST, "/api/admin/notifications")
                .andExpect(content().json("{\"audience\":\"EXPERIMENT\",\"experimentId\":\"" + experiment + "\",\"variantId\":\"" + variant + "\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"sent\":7}"));

        var result = service.adminSendNotification(body);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).isEqualTo(7);
    }

    @Test
    void adminSendNotification_ofASingleNotification_hasNoCount() {
        var body = new fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody("t", "b",
                fr.enimaloc.catapult.common.dto.notification.Severity.INFO, null, null, null, null);
        expect(HttpMethod.POST, "/api/admin/notifications").andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"" + NOTIF_ID + "\",\"title\":\"t\"}"));

        var result = service.adminSendNotification(body);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).isNull();
    }

    @Test
    void adminNotificationAudienceCount_postsTheIds_andIsNullOnFailure() {
        UUID experiment = UUID.randomUUID();
        UUID variant = UUID.randomUUID();
        expect(HttpMethod.POST, "/api/admin/notifications/audience-count")
                .andExpect(content().json("{\"audience\":\"EXPERIMENT\",\"experimentId\":\"" + experiment + "\",\"variantId\":\"" + variant + "\"}"))
                .andRespond(withSuccess("{\"count\":12}", MediaType.APPLICATION_JSON));
        expect(HttpMethod.POST, "/api/admin/notifications/audience-count").andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(service.adminNotificationAudienceCount(experiment, variant)).isEqualTo(12L);
        assertThat(service.adminNotificationAudienceCount(experiment, null)).isNull();
    }

    @Test
    void adminDtddProposals_readsTheListOfTheStatus() {
        respond(HttpMethod.GET, "/api/admin/dtdd-mapping/proposals?status=APPROVED", "[{\"id\":\"" + NOTIF_ID
                + "\",\"createdAt\":\"2026-10-18T10:00:00Z\",\"igdbId\":\"100\",\"proposedDtddId\":4521,"
                + "\"proposer\":{\"id\":\"" + NOTIF_ID + "\",\"twitchUsername\":\"alice\"},\"reason\":\"r\",\"status\":\"APPROVED\"}]");

        var proposals = service.adminDtddProposals("APPROVED");

        assertThat(proposals).hasSize(1);
        assertThat(proposals.getFirst().igdbId()).isEqualTo("100");
        assertThat(proposals.getFirst().proposedDtddId()).isEqualTo(4521L);
        assertThat(proposals.getFirst().proposer().twitchUsername()).isEqualTo("alice");
    }

    @Test
    void adminApproveDtddProposal_postsAndReturnsTheStatus() {
        expect(HttpMethod.POST, "/api/admin/dtdd-mapping/proposals/" + NOTIF_ID + "/approve")
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(service.adminApproveDtddProposal(NOTIF_ID)).isEqualTo(409);
    }

    @Test
    void adminRejectDtddProposal_sendsTheReasonOnlyWhenGiven() {
        expect(HttpMethod.POST, "/api/admin/dtdd-mapping/proposals/" + NOTIF_ID + "/reject")
                .andExpect(content().json("{}", true)).andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/dtdd-mapping/proposals/" + NOTIF_ID + "/reject")
                .andExpect(content().json("{}", true)).andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/dtdd-mapping/proposals/" + NOTIF_ID + "/reject")
                .andExpect(content().json("{\"reason\":\"dup\"}", true)).andRespond(withNoContent());

        assertThat(service.adminRejectDtddProposal(NOTIF_ID, "")).isEqualTo(204);
        assertThat(service.adminRejectDtddProposal(NOTIF_ID, "  ")).isEqualTo(204);
        assertThat(service.adminRejectDtddProposal(NOTIF_ID, "dup")).isEqualTo(204);
    }

    @Test
    void adminGroups_readsTheList() {
        respond(HttpMethod.GET, "/api/admin/groups", "[{\"id\":\"" + NOTIF_ID
                + "\",\"key\":\"beta\",\"name\":\"Beta\",\"description\":null,\"memberCount\":3}]");

        var groups = service.adminGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().key()).isEqualTo("beta");
        assertThat(groups.getFirst().memberCount()).isEqualTo(3);
    }

    @Test
    void adminGroupActions_postTheirShapes_andReturnTheStatus() {
        expect(HttpMethod.POST, "/api/admin/groups")
                .andExpect(content().json("{\"key\":\"beta\",\"name\":\"Beta\",\"description\":\"d\"}", true))
                .andRespond(withStatus(HttpStatus.CREATED));
        expect(HttpMethod.POST, "/api/admin/groups/" + NOTIF_ID + "/rename")
                .andExpect(content().json("{\"name\":\"N\",\"description\":\"D\"}", true)).andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/groups/" + NOTIF_ID + "/members")
                .andExpect(content().json("{\"twitchUsername\":\"alice\"}", true)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        expect(HttpMethod.POST, "/api/admin/groups/" + NOTIF_ID + "/delete").andRespond(withNoContent());

        assertThat(service.adminCreateGroup("beta", "Beta", "d")).isEqualTo(201);
        assertThat(service.adminRenameGroup(NOTIF_ID, "N", "D")).isEqualTo(204);
        assertThat(service.adminAddGroupMember(NOTIF_ID, "alice")).isEqualTo(404);
        assertThat(service.adminDeleteGroup(NOTIF_ID)).isEqualTo(204);
    }

    @Test
    void adminWhitelist_readsThePage() {
        respond(HttpMethod.GET, "/api/admin/whitelist", "{\"entries\":[{\"twitchId\":\"123\"}],\"resolvedUsernames\":{\"123\":\"alice\"},"
                + "\"whitelistEnabled\":true,\"inviteEnabled\":true,\"globalMaxMembers\":null,\"defaultMaxUses\":3,"
                + "\"defaultCanReinvite\":false,\"globalCapReached\":false,\"invites\":[{\"id\":\"" + NOTIF_ID
                + "\",\"ownerId\":\"" + GROUP_ID + "\",\"ownerUsername\":\"bob\",\"code\":\"ABC234\",\"maxUses\":null,"
                + "\"useCount\":2,\"canReinvite\":null,\"createdAt\":\"2026-10-01T10:00:00Z\",\"redemptions\":[]}]}");

        var page = service.adminWhitelist();

        assertThat(page.entries()).extracting("twitchId").containsExactly("123");
        assertThat(page.resolvedUsernames()).containsEntry("123", "alice");
        assertThat(page.globalMaxMembers()).isNull();
        assertThat(page.defaultMaxUses()).isEqualTo(3);
        assertThat(page.invites().getFirst().ownerUsername()).isEqualTo("bob");
        assertThat(page.invites().getFirst().maxUses()).isNull();
        assertThat(page.invites().getFirst().useCount()).isEqualTo(2);
    }

    @Test
    void whitelistActions_postTheirShapes_andReturnTheStatus() {
        expect(HttpMethod.POST, "/api/admin/whitelist/toggle").andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/whitelist/add")
                .andExpect(content().json("{\"twitchId\":\"123\"}", true)).andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/whitelist/123/delete").andRespond(withStatus(HttpStatus.NOT_FOUND));
        expect(HttpMethod.POST, "/api/admin/whitelist/invite/toggle").andRespond(withNoContent());

        assertThat(service.adminToggleWhitelist()).isEqualTo(204);
        assertThat(service.adminAddWhitelist("123")).isEqualTo(204);
        assertThat(service.adminDeleteWhitelist("123")).isEqualTo(404);
        assertThat(service.adminToggleInvites()).isEqualTo(204);
    }

    @Test
    void deleteWhitelist_encodesALegacyId() {
        expect(HttpMethod.POST, "/api/admin/whitelist/old%20user%2F1/delete").andRespond(withNoContent());

        assertThat(service.adminDeleteWhitelist("old user/1")).isEqualTo(204);
    }

    @Test
    void settings_sendNullsForEmptyNumbers() {
        expect(HttpMethod.POST, "/api/admin/whitelist/invite/settings")
                .andExpect(content().json("{\"globalMaxMembers\":null,\"defaultMaxUses\":3,\"defaultCanReinvite\":false}", true))
                .andRespond(withNoContent());

        assertThat(service.adminSaveInviteSettings(null, 3, false)).isEqualTo(204);
    }

    @Test
    void quota_sendsNullsForTheDefault() {
        expect(HttpMethod.POST, "/api/admin/whitelist/invite/" + NOTIF_ID + "/quota")
                .andExpect(content().json("{\"maxUses\":null,\"canReinvite\":true}", true)).andRespond(withNoContent());

        assertThat(service.adminSaveInviteQuota(NOTIF_ID, null, true)).isEqualTo(204);
    }

    @Test
    void adminMember_andTargeting_readTheirEndpoints() {
        respond(HttpMethod.GET, "/api/admin/members/" + NOTIF_ID, "{\"id\":\"" + NOTIF_ID + "\",\"twitchUsername\":\"alice\"}");
        respond(HttpMethod.GET, "/api/admin/members/" + NOTIF_ID + "/targeting",
                "{\"flags\":[{\"key\":\"beta\",\"value\":null},{\"key\":\"tier\",\"value\":\"2\"}],\"groupKeys\":[\"staff\"]}");

        assertThat(service.adminMember(NOTIF_ID).twitchUsername()).isEqualTo("alice");
        var targeting = service.adminMemberTargeting(NOTIF_ID);
        assertThat(targeting.flags()).containsExactly(new MemberTargetingDto.Flag("beta", null), new MemberTargetingDto.Flag("tier", "2"));
        assertThat(targeting.groupKeys()).containsExactly("staff");
    }

    @Test
    void adminMemberTargetingActions_postTheirShapes_andReturnTheStatus() {
        expect(HttpMethod.POST, "/api/admin/members/" + NOTIF_ID + "/flags")
                .andExpect(content().json("{\"key\":\"tier\",\"value\":\"2\"}", true)).andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/members/" + NOTIF_ID + "/flags/tier/delete").andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/members/" + NOTIF_ID + "/groups")
                .andExpect(content().json("{\"groupKey\":\"staff\"}", true)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        expect(HttpMethod.POST, "/api/admin/members/" + NOTIF_ID + "/groups/" + GROUP_ID + "/delete").andRespond(withNoContent());

        assertThat(service.adminSetMemberFlag(NOTIF_ID, "tier", "2")).isEqualTo(204);
        assertThat(service.adminDeleteMemberFlag(NOTIF_ID, "tier")).isEqualTo(204);
        assertThat(service.adminAddMemberToGroup(NOTIF_ID, "staff")).isEqualTo(404);
        assertThat(service.adminRemoveMemberFromGroup(NOTIF_ID, GROUP_ID)).isEqualTo(204);
    }

    @Test
    void deleteFlag_encodesTheKey() {
        expect(HttpMethod.POST, "/api/admin/members/" + NOTIF_ID + "/flags/a%2Fb%20%25%C3%A9/delete").andRespond(withNoContent());

        assertThat(service.adminDeleteMemberFlag(NOTIF_ID, "a/b %é")).isEqualTo(204);
    }

    @Test
    void adminSetMemberFlag_withoutAValue_sendsANullOne() {
        expect(HttpMethod.POST, "/api/admin/members/" + NOTIF_ID + "/flags")
                .andExpect(content().json("{\"key\":\"beta\",\"value\":null}", true)).andRespond(withNoContent());

        assertThat(service.adminSetMemberFlag(NOTIF_ID, "beta", null)).isEqualTo(204);
    }

    @Test
    void adminDeleteNotification_returnsTheStatus() {
        expect(HttpMethod.DELETE, "/api/admin/notifications/" + NOTIF_ID).andRespond(withNoContent());

        assertThat(service.adminDeleteNotification(NOTIF_ID)).isEqualTo(204);
    }

    @Test
    void adminImpersonate_postsTheUsername_andReadsTheToken_keepingARefusalsStatus() {
        expect(HttpMethod.POST, "/api/admin/impersonate")
                .andExpect(content().json("{\"username\":\"alice\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"token\":\"target-token\"}", MediaType.APPLICATION_JSON));
        expect(HttpMethod.POST, "/api/admin/impersonate").andRespond(withStatus(HttpStatus.FORBIDDEN));
        expect(HttpMethod.POST, "/api/admin/impersonate").andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(service.adminImpersonate("alice")).isEqualTo(new ApiClient.Result<>(200, "target-token"));
        assertThat(service.adminImpersonate("alice")).isEqualTo(new ApiClient.Result<>(403, null));
        assertThat(service.adminImpersonate("ghost")).isEqualTo(new ApiClient.Result<>(404, null));
    }

    @Test
    void adminImpersonate_isBadGateway_withoutAToken() {
        expect(HttpMethod.POST, "/api/admin/impersonate").andRespond(withSuccess("{\"token\":\" \"}", MediaType.APPLICATION_JSON));
        expect(HttpMethod.POST, "/api/admin/impersonate").andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(service.adminImpersonate("alice")).isEqualTo(new ApiClient.Result<>(502, null));
        assertThat(service.adminImpersonate("alice")).isEqualTo(new ApiClient.Result<>(502, null));
    }

    @Test
    void adminImpersonate_unreachable_is502() {
        expect(HttpMethod.POST, "/api/admin/impersonate").andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });

        assertThat(service.adminImpersonate("alice")).isEqualTo(new ApiClient.Result<>(502, null));
    }

    @Test
    void adminPreviewNotification_returnsTheBodyHtml() {
        expect(HttpMethod.POST, "/api/admin/notifications/preview").andExpect(content().json("{\"body\":\"**a**\"}"))
                .andRespond(withSuccess("{\"bodyHtml\":\"<p><strong>a</strong></p>\"}", MediaType.APPLICATION_JSON));

        assertThat(service.adminPreviewNotification("**a**"))
                .isEqualTo(new ApiClient.Result<>(200, "<p><strong>a</strong></p>"));
    }

    @Test
    void adminPreviewNotification_keepsTheRefusalsStatus() {
        expect(HttpMethod.POST, "/api/admin/notifications/preview").andRespond(withStatus(HttpStatus.BAD_REQUEST));
        expect(HttpMethod.POST, "/api/admin/notifications/preview").andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(service.adminPreviewNotification("")).isEqualTo(new ApiClient.Result<>(400, null));
        assertThat(service.adminPreviewNotification("x")).isEqualTo(new ApiClient.Result<>(404, null));
    }

    @Test
    void adminPreviewNotification_isBadGatewayWithoutHtml() {
        expect(HttpMethod.POST, "/api/admin/notifications/preview").andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(service.adminPreviewNotification("x")).isEqualTo(new ApiClient.Result<>(502, null));
    }

    @Test
    void adminBroadcast_sendsTheNameDiscriminator_andRelaysA429() {
        expect(HttpMethod.POST, "/api/admin/broadcast")
                .andExpect(content().json("{\"name\":\"alert.info\",\"title\":\"t\",\"body\":\"b\",\"ttlSeconds\":0}"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(service.adminBroadcast(new fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto.AlertInfo("t", "b", 0)))
                .isEqualTo(429);
    }

    @Test
    void adminApiKeys_readEachProvidersPage() {
        respond(HttpMethod.GET, "/api/admin/steam-keys", "{\"keys\":[{\"id\":\"k1\",\"masked\":\"abcd…wxyz\",\"owner\":null,"
                + "\"blocked\":true,\"blockedForSeconds\":30}],\"steamEnabled\":true}");
        respond(HttpMethod.GET, "/api/admin/dtdd-keys", "{\"keys\":[],\"dtddEnabled\":false}");

        var steam = service.adminApiKeys(fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.STEAM);
        var dtdd = service.adminApiKeys(fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.DTDD);

        assertThat(steam.enabled()).isTrue();
        assertThat(steam.keys()).singleElement().satisfies(k -> {
            assertThat(k.id()).isEqualTo("k1");
            assertThat(k.masked()).isEqualTo("abcd…wxyz");
            assertThat(k.owner()).isNull();
            assertThat(k.blocked()).isTrue();
            assertThat(k.blockedForSeconds()).isEqualTo(30);
        });
        assertThat(dtdd.enabled()).isFalse();
        assertThat(dtdd.keys()).isEmpty();
    }

    @Test
    void apiKeyActions_postTheirShapes_andReturnTheStatus() {
        expect(HttpMethod.POST, "/api/admin/steam-keys/add")
                .andExpect(content().json("{\"apiKey\":\"abc\"}", true)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        expect(HttpMethod.POST, "/api/admin/dtdd-keys/delete")
                .andExpect(content().json("{\"keyId\":\"id/1 é\"}", true)).andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/dtdd-keys/refresh").andRespond(withNoContent());

        var steam = fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.STEAM;
        var dtdd = fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider.DTDD;
        assertThat(service.adminAddApiKey(steam, "abc")).isEqualTo(400);
        assertThat(service.adminDeleteApiKey(dtdd, "id/1 é")).isEqualTo(204);
        assertThat(service.adminRefreshApiKeys(dtdd)).isEqualTo(204);
    }

    @Test
    void adminMinecraftAccounts_readCatapultApisAccountDto() {
        respond(HttpMethod.GET, "/api/admin/minecraft-accounts", "[{\"id\":\"00000000-0000-0000-0000-00000000000a\","
                + "\"label\":\"Bot1\",\"minecraftUsername\":\"CatapultBot1\",\"fillOrder\":2,\"friendLimitReached\":true,"
                + "\"enabled\":false,\"linkCount\":7}]");

        assertThat(service.adminMinecraftAccounts()).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
            assertThat(a.label()).isEqualTo("Bot1");
            assertThat(a.minecraftUsername()).isEqualTo("CatapultBot1");
            assertThat(a.fillOrder()).isEqualTo(2);
            assertThat(a.linkCount()).isEqualTo(7);
            assertThat(a.enabled()).isFalse();
            assertThat(a.friendLimitReached()).isTrue();
        });
    }

    @Test
    void adminMinecraftAccounts_isNullWhenTheApiFails() {
        expect(HttpMethod.GET, "/api/admin/minecraft-accounts").andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(service.adminMinecraftAccounts()).isNull();
    }

    @Test
    void minecraftAccountActions_sendTheirShapes_andReturnTheStatus() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        expect(HttpMethod.PATCH, "/api/admin/minecraft-accounts/" + id)
                .andExpect(content().json("{\"friendLimitReached\":false}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        expect(HttpMethod.DELETE, "/api/admin/minecraft-accounts/" + id)
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"linked\"}"));

        assertThat(service.adminPatchMinecraftAccount(id, Map.of("friendLimitReached", false))).isEqualTo(200);
        assertThat(service.adminDeleteMinecraftAccount(id)).isEqualTo(409);
    }

    @Test
    void adminMinecraftDeviceCode_readsTheSnakeCaseAnswer_andKeepsARefusalsStatusAndMessage() {
        expect(HttpMethod.POST, "/api/admin/minecraft-accounts/device-code").andRespond(withSuccess(
                "{\"device_code\":\"dc-1\",\"user_code\":\"ABCD-1234\",\"verification_uri\":\"https://www.microsoft.com/link\","
                        + "\"interval\":5,\"expires_in\":900}", MediaType.APPLICATION_JSON));
        expect(HttpMethod.POST, "/api/admin/minecraft-accounts/device-code")
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Device-code Microsoft refusé: AADSTS70002\"}"));
        expect(HttpMethod.POST, "/api/admin/minecraft-accounts/device-code").andRespond(withStatus(HttpStatus.NOT_FOUND));
        expect(HttpMethod.POST, "/api/admin/minecraft-accounts/device-code").andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(service.adminMinecraftDeviceCode()).isEqualTo(new fr.enimaloc.catapult.service.ApiService.MinecraftDeviceCodeStart(200,
                new fr.enimaloc.catapult.common.dto.admin.MinecraftDeviceCodeDto("dc-1", "ABCD-1234", "https://www.microsoft.com/link", 5, 900), null));
        assertThat(service.adminMinecraftDeviceCode()).isEqualTo(new fr.enimaloc.catapult.service.ApiService.MinecraftDeviceCodeStart(502,
                null, "Device-code Microsoft refusé: AADSTS70002"));
        assertThat(service.adminMinecraftDeviceCode()).isEqualTo(new fr.enimaloc.catapult.service.ApiService.MinecraftDeviceCodeStart(404, null, null));
        // a 200 without a device code is no code at all
        assertThat(service.adminMinecraftDeviceCode().status()).isEqualTo(502);
    }

    @Test
    void createAndReauth_postTheDeviceCode_andKeepTheStatusAndBody() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        expect(HttpMethod.POST, "/api/admin/minecraft-accounts")
                .andExpect(content().json("{\"deviceCode\":\"dc-1\",\"label\":\"Bot1\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{\"status\":\"PENDING\"}"));
        expect(HttpMethod.POST, "/api/admin/minecraft-accounts")
                .andExpect(content().json("{\"deviceCode\":\"dc-1\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"label\":\"Compte Minecraft\",\"minecraftUsername\":\"CatapultBot1\"}", MediaType.APPLICATION_JSON));
        expect(HttpMethod.POST, "/api/admin/minecraft-accounts/" + id + "/reauth")
                .andExpect(content().json("{\"deviceCode\":\"dc-2\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"chain failed\"}"));

        assertThat(service.adminCreateMinecraftAccount("dc-1", "Bot1").status()).isEqualTo(202);
        var created = service.adminCreateMinecraftAccount("dc-1", null);
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body()).containsEntry("minecraftUsername", "CatapultBot1");
        var failed = service.adminReauthMinecraftAccount(id, "dc-2");
        assertThat(failed.status()).isEqualTo(502);
        assertThat(failed.body()).containsEntry("message", "chain failed");
    }

    @Test
    void adminTws_readTheListAndOneDefinition_withTheIdAsAUriVariable() {
        respond(HttpMethod.GET, "/api/admin/tw", "[{\"id\":\"gore\",\"label\":\"Gore\",\"description\":null,"
                + "\"enabled\":false,\"sortOrder\":7}]");
        respond(HttpMethod.GET, "/api/admin/tw/a%2Fb%20%C3%A9", "{\"id\":\"a/b é\",\"label\":\"L\","
                + "\"description\":\"d\",\"enabled\":true,\"sortOrder\":0}");

        assertThat(service.adminTws()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo("gore");
            assertThat(d.label()).isEqualTo("Gore");
            assertThat(d.description()).isNull();
            assertThat(d.enabled()).isFalse();
            assertThat(d.sortOrder()).isEqualTo(7);
        });
        assertThat(service.adminTw("a/b é").description()).isEqualTo("d");
    }

    @Test
    void adminCreateTw_postsTheDefinition_andReturnsTheStatus() {
        expect(HttpMethod.POST, "/api/admin/tw")
                .andExpect(content().json("{\"id\":\"gore\",\"label\":\"Gore\",\"sortOrder\":3}"))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(service.adminCreateTw("gore", "Gore", 3)).isEqualTo(201);
    }

    @Test
    void rebuild_mapsEachModeToItsRoute() {
        expectPost("/api/admin/tw/rebuild");
        expectPost("/api/admin/tw/rebuild/force");
        expect(HttpMethod.POST, "/api/admin/tw/rebuild/force-pinned").andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(service.adminRebuildTws(fr.enimaloc.catapult.common.dto.admin.TwRebuild.NON_OVERRIDDEN)).isEqualTo(204);
        assertThat(service.adminRebuildTws(fr.enimaloc.catapult.common.dto.admin.TwRebuild.UNTOUCHED)).isEqualTo(204);
        assertThat(service.adminRebuildTws(fr.enimaloc.catapult.common.dto.admin.TwRebuild.FORCE_PINNED)).isEqualTo(503);
    }

    @Test
    void twKeywords_listAddAndTest_useTheSteamKeywordRoutes() {
        respond(HttpMethod.GET, "/api/admin/tw/violence/steam-keywords", "[\"blood\",\"gore\"]");
        expect(HttpMethod.POST, "/api/admin/tw/violence/steam-keywords")
                .andExpect(content().json("{\"keyword\":\"blood\"}", true)).andRespond(withNoContent());
        expect(HttpMethod.POST, "/api/admin/tw/violence/steam-keywords/test")
                .andExpect(content().json("{\"appId\":\"730\",\"draftKeyword\":\"gor\"}", true))
                .andRespond(withSuccess("{\"notes\":\"gore\",\"matchedKeywords\":[\"gore\"],"
                        + "\"draftKeywordMatch\":null,\"matchedContentDescriptorIds\":[1,5]}", MediaType.APPLICATION_JSON));

        assertThat(service.adminTwKeywords("violence")).containsExactly("blood", "gore");
        assertThat(service.adminAddTwKeyword("violence", "blood")).isEqualTo(204);
        var test = service.adminTestTwKeyword("violence", "730", "gor");
        assertThat(test.matchedKeywords()).containsExactly("gore");
        assertThat(test.matchedContentDescriptorIds()).containsExactly(1, 5);
        assertThat(test.draftKeywordMatch()).isNull();
        assertThat(test.notes()).isEqualTo("gore");
    }

    @Test
    void twTest_whenTheApiRefuses_isNull() {
        expect(HttpMethod.POST, "/api/admin/tw/violence/steam-keywords/test").andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(service.adminTestTwKeyword("violence", "730", null)).isNull();
    }

    @Test
    void removeKeyword_encodesIt() {
        expect(HttpMethod.DELETE, "/api/admin/tw/violence/steam-keywords/gore%2Fblood%20%C3%A9").andRespond(withNoContent());

        assertThat(service.adminRemoveTwKeyword("violence", "gore/blood é")).isEqualTo(204);
    }
}
