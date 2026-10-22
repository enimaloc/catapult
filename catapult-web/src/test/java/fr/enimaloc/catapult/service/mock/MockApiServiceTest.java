package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.ChannelConnectionChangedEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.event.binding.BindingDeletedEvent;
import fr.enimaloc.catapult.event.binding.BindingIgnoredStateEvent;
import fr.enimaloc.catapult.event.binding.BindingUpdatedEvent;
import fr.enimaloc.catapult.event.binding.CclStateEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftDisconnectedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftEnrollEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenDeletedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSavedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSharedStateEvent;
import fr.enimaloc.catapult.event.tw.TwEnabledStateEvent;
import fr.enimaloc.catapult.event.tw.TwResetEvent;
import fr.enimaloc.catapult.event.tw.TwUpdatedEvent;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.twitchat.TwitchatDispatchHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockApiServiceTest {

    private final List<Object> events = new ArrayList<>();
    private final TwitchatDispatchHub hub = org.mockito.Mockito.mock(TwitchatDispatchHub.class);
    private final MockApiService service = new MockApiService(events::add, hub);

    private MockData data;
    private String username;
    private String bindingId;

    /** Logs in through the mock flow and binds the resulting token to a fresh "browser session". */
    private void loginAs(String code) {
        String token = service.exchangeCode(code).token();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        data = service.getData();
        username = data.getChannelDto().twitchUsername();
        // A random session (digit code) may draw no binding at all.
        bindingId = data.getPrimaryBinding() == null ? null : data.getPrimaryBinding().id();
    }

    @BeforeEach
    void setUp() {
        loginAs("{}");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A visitor has no mock data: like catapult-api's 401, the channel page is null rather than a crash. */
    @Test
    void channelPage_isNullForAVisitor() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(service.channelPage("anyone", 0, null, null)).isNull();
    }

    @Test
    void deleteAccount_onlyWhenTheNameMatches_ignoringCase() {
        assertThat(service.accountInfo().status()).isEqualTo("ACTIVE");

        assertThat(service.deleteAccount(username, "WRONG")).isEqualTo(204);
        assertThat(service.accountInfo().status()).isEqualTo("ACTIVE");

        assertThat(service.deleteAccount(username, username.toUpperCase())).isEqualTo(204);
        var pending = service.accountInfo();
        assertThat(pending.status()).isEqualTo("PENDING_DELETION");
        assertThat(pending.deletionRequestedAt()).isNotBlank();

        assertThat(service.cancelAccountDeletion(username)).isEqualTo(204);
        assertThat(service.accountInfo().status()).isEqualTo("ACTIVE");
        assertThat(service.accountInfo().deletionRequestedAt()).isNull();
    }

    @Test
    void invitePage_hasACodeAndRegenerateChangesIt() {
        var before = service.invitePage();

        assertThat(before.canInvite()).isTrue();
        assertThat(before.code()).matches("[A-Z2-9]{1,12}");
        assertThat(before.inviteUrl()).endsWith("/join?invite=" + before.code());
        assertThat(before.redemptions()).isNotNull();

        assertThat(service.regenerateInvite()).isEqualTo(204);

        var after = service.invitePage();
        assertThat(after.code()).isNotEqualTo(before.code());
        assertThat(after.inviteUrl()).endsWith("/join?invite=" + after.code());
    }

    @Test
    void invitePage_isNullForAVisitor() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(service.invitePage()).isNull();
        assertThat(service.regenerateInvite()).isEqualTo(401);
    }

    @Test
    void deleteAccount_isRefusedForAnotherChannel() {
        assertThat(service.deleteAccount("someone-else", "someone-else")).isEqualTo(403);
        assertThat(service.cancelAccountDeletion("someone-else")).isEqualTo(403);
        assertThat(service.accountInfo().status()).isEqualTo("ACTIVE");
    }

    @Test
    void accountDeletionDelayDays_isTheApiDefault() {
        assertThat(service.accountDeletionDelayDays()).isEqualTo(7);
    }

    @Test
    void steamConnect_linksAtOnce_andDisconnectUnlinks_eachTellingTheCard() {
        data.setProviders(true, false, false, false);

        assertThat(service.steamConnectUrl()).isEqualTo("/connect/steam/close");
        assertThat(data.isHasSteam()).isTrue();
        assertThat(events).contains(new ChannelConnectionChangedEvent(username, "STEAM", true));
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(SteamConnectionStateEvent.class,
                steam -> assertThat(steam.connected()).isTrue()));

        events.clear();
        assertThat(service.steamDisconnect()).isEqualTo(204);
        assertThat(data.isHasSteam()).isFalse();
        assertThat(events).contains(new ChannelConnectionChangedEvent(username, "STEAM", false),
                new SteamConnectionStateEvent(username, false, false, false, false));
    }

    @Test
    void steamConnect_unavailableWithoutSteam_orForAVisitor() {
        data.setProviders(false, false, false, false);
        assertThat(service.steamConnectUrl()).isNull();

        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(service.steamConnectUrl()).isNull();
        assertThat(service.steamDisconnect()).isEqualTo(401);
    }

    @Test
    void changelogAndStatus_answerAsAnUpCatapultApi() {
        assertThat(service.changelog()).isNotEmpty().allSatisfy(section -> assertThat(section.groups()).isNotEmpty());
        assertThat(service.apiAvailable()).isTrue();
    }

    private BindingDto binding() {
        return data.getPrimaryBinding();
    }

    // --- sessions --------------------------------------------------------------------------------

    /** Shaped as catapult-api's JWT, so that ApiClient reads the login's claims in the mock too. */
    @Test
    void exchangeCode_mintsAJwtShapedToken_withTheMembersLogin() {
        assertThat(ApiClient.currentLogin()).isEqualTo(username);
        assertThat(ApiClient.currentSubject()).isEqualTo(data.getChannelDto().id());
        assertThat(ApiClient.currentRoles()).containsExactly("ROLE_USER");
    }

    @Test
    void exchangeCode_mintsADistinctTokenPerLogin() {
        assertThat(service.exchangeCode("{}").token()).isNotEqualTo(service.exchangeCode("{}").token());
    }

    @Test
    void eachSessionGetsItsOwnData() {
        MockData first = service.getData();
        loginAs("{\"username\": \"other\"}");

        assertThat(service.getData()).isNotSameAs(first);
        assertThat(service.getData().getChannelDto().twitchUsername()).isEqualTo("other");
    }

    @Test
    void digitCodes_getRandomSessions() {
        loginAs("0");

        assertThat(service.getData()).isNotNull();
    }

    @Test
    void unknownToken_regeneratesTheSameSessionEveryTime() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, "lost-after-restart");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        MockData regenerated = service.getData();

        assertThat(regenerated.getChannelDto()).isEqualTo(MockData.randomFrom("lost-after-restart").getChannelDto());
        assertThat(service.getData()).isSameAs(regenerated);
    }

    @Test
    void noSession_hasNoData() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(service.getData()).isNull();

        RequestContextHolder.resetRequestAttributes();
        assertThat(service.getData()).isNull();
    }

    // --- reads -----------------------------------------------------------------------------------

    @Test
    void readsComeFromTheSessionsData() {
        assertThat(service.channelList()).isEqualTo(data.getChannelList());
        assertThat(service.channelPage(username, 0, null, null).channelUser()).isEqualTo(data.getChannelUser());
        assertThat(service.channelSettings(username)).isEqualTo(data.getUserSettings());
        assertThat(service.minecraftStatus(username)).isEqualTo(data.getMinecraftLink());
    }

    @Test
    void currentBinding_isTheDetectedGames_nullWhenItHasNone() {
        loginAs("{}");
        org.junit.jupiter.api.Assumptions.assumeTrue(bindingId != null);
        data.setDetectedGame(bindingId, "STEAM", "Valheim");
        assertThat(service.currentBinding(username)).isEqualTo(data.bindingById(bindingId));

        data.setDetectedGame(null, "STEAM", "Unbound");
        assertThat(service.currentBinding(username)).isNull();
        data.setDetectedGame("no-such-binding", "STEAM", "Gone");
        assertThat(service.currentBinding(username)).isNull();
    }

    @Test
    void searchGames_matchesCategoryNamesIgnoringCase() {
        assertThat(service.searchGames(username, "VAL")).isEqualTo(List.of(Map.of("id", "508455", "name", "Valheim")));
        assertThat((List<?>) service.searchGames(username, "")).hasSize(MockPresets.CATEGORIES.size());
        assertThat((List<?>) service.searchGames(username, "zzz")).isEmpty();
    }

    @Test
    void dtddIsInert_butAProposalIsTold() {
        assertThat(service.dtddMappingStatus(username)).isEqualTo(new DtddMappingStatusDto(null, null, false, null));
        assertThat(service.dtddSearch("doom")).isEqualTo(new SearchResponse(List.of()));
        service.dtddValidate("1");
        assertThat(events).isEmpty();
        service.dtddPropose("1", 2L, "r");
        assertThat(events).containsExactly(new fr.enimaloc.catapult.event.DtddMappingChangedEvent(username));
    }

    @Test
    void dtddPropose_reachesTheAdminReview() {
        int before = service.adminDtddProposals("PENDING").size();

        service.dtddPropose("1", 2L, "r");

        assertThat(service.adminDtddProposals("PENDING")).hasSize(before + 1);
        var first = service.adminDtddProposals("PENDING").getFirst();
        assertThat(service.adminApproveDtddProposal(first.id())).isEqualTo(204);
        assertThat(service.adminApproveDtddProposal(first.id())).isEqualTo(409);
        assertThat(service.adminRejectDtddProposal(java.util.UUID.randomUUID(), "x")).isEqualTo(404);
    }

    @Test
    void groups_addMemberByLogin_404ForAnUnknownLoginOrGroup() {
        assertThat(service.adminCreateGroup("crew", "Crew", null)).isEqualTo(201);
        var group = service.adminGroups().stream().filter(g -> g.key().equals("crew")).findFirst().orElseThrow();
        String login = allMembers().getFirst().twitchUsername();

        assertThat(service.adminAddGroupMember(group.id(), "nobody-here")).isEqualTo(404);
        assertThat(service.adminAddGroupMember(java.util.UUID.randomUUID(), login)).isEqualTo(404);
        assertThat(service.adminAddGroupMember(group.id(), " " + login.toUpperCase() + " ")).isEqualTo(204);
        assertThat(service.adminGroups().stream().filter(g -> g.key().equals("crew")).findFirst().orElseThrow().memberCount()).isEqualTo(1);
        assertThat(service.adminDeleteGroup(group.id())).isEqualTo(204);
        assertThat(service.adminDeleteGroup(group.id())).isEqualTo(404);
    }

    @Test
    void targeting_flagsAndGroups_followTheActions() {
        var member = selfMember();
        var group = service.adminGroups().getFirst();

        assertThat(service.adminMember(member.id()).twitchUsername()).isEqualTo(member.twitchUsername());
        assertThat(service.adminMember(java.util.UUID.randomUUID())).isNull();
        assertThat(service.adminMemberTargeting(java.util.UUID.randomUUID())).isNull();

        assertThat(service.adminSetMemberFlag(member.id(), "tier", "2")).isEqualTo(204);
        assertThat(service.adminSetMemberFlag(member.id(), " ", "2")).isEqualTo(400);
        assertThat(service.adminSetMemberFlag(java.util.UUID.randomUUID(), "tier", "2")).isEqualTo(404);
        assertThat(service.adminAddMemberToGroup(member.id(), group.key())).isEqualTo(204);
        assertThat(service.adminAddMemberToGroup(member.id(), "nope")).isEqualTo(404);

        var targeting = service.adminMemberTargeting(member.id());
        assertThat(targeting.flags()).containsExactly(new fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto.Flag("tier", "2"));
        assertThat(targeting.groupKeys()).containsExactly(group.key());

        assertThat(service.adminRemoveMemberFromGroup(member.id(), group.id())).isEqualTo(204);
        assertThat(service.adminRemoveMemberFromGroup(member.id(), java.util.UUID.randomUUID())).isEqualTo(404);
        assertThat(service.adminDeleteMemberFlag(member.id(), "tier")).isEqualTo(204);
        assertThat(service.adminMemberTargeting(member.id()).flags()).isEmpty();
        assertThat(service.adminMemberTargeting(member.id()).groupKeys()).isEmpty();
    }

    @Test
    void noOpActions_changeNothing() {
        service.refreshSteamProfileCache(username);

        assertThat(events).isEmpty();
    }

    @Test
    void recheckGame_tellsTheGameTheMockHas_asCatapultApiWould() {
        service.recheckGame(username);

        assertThat(events).singleElement().isInstanceOf(GameChangedEvent.class);
    }

    // --- mutations publish what changed ----------------------------------------------------------

    @Test
    void toggleBot() {
        service.toggleBot(username);

        assertThat(data.isBotEnabled()).isFalse();
        assertThat(events).containsExactly(new BotStateChangedEvent(username, false));
    }

    @Test
    void bindingToggles() {
        service.cclToggle(username, bindingId, false);
        service.ignoredToggle(username, bindingId, true);

        assertThat(binding().cclEnabled()).isFalse();
        assertThat(binding().ignored()).isTrue();
        assertThat(events).containsExactly(new CclStateEvent(username, bindingId, false),
                new BindingIgnoredStateEvent(username, bindingId, true));
    }

    @Test
    void updateBinding() {
        service.updateBinding(username, bindingId, "7", "Doom", Set.of("Gore"));

        assertThat(binding().twitchGameName()).isEqualTo("Doom");
        assertThat(events).containsExactly(new BindingUpdatedEvent(username, bindingId, "7", "Doom", Set.of("Gore")));
    }

    @Test
    void deleteBinding() {
        service.deleteBinding(username, bindingId);

        assertThat(data.getBinding()).isEmpty();
        assertThat(events).containsExactly(new BindingDeletedEvent(username, bindingId));
    }

    @Test
    void triggerWarnings_areAttributedToTheSessionsChannel() {
        service.saveTws(bindingId, Set.of("spiders"));
        service.toggleTwEnabled(bindingId, false);
        service.resetTws(bindingId);

        assertThat(binding().twEnabled()).isFalse();
        assertThat(binding().twOverride()).isFalse();
        assertThat(events).containsExactly(new TwUpdatedEvent(username, bindingId, Set.of("spiders")),
                new TwEnabledStateEvent(username, bindingId, false),
                new TwResetEvent(username, bindingId));
    }

    @Test
    void steamToken() {
        service.saveSteamToken(username, "KEY", true);
        service.steamTokenSharing(username, false);
        service.deleteSteamToken(username);

        assertThat(events).containsExactly(new SteamTokenSavedEvent(username, true),
                new SteamTokenSharedStateEvent(username, false), new SteamTokenDeletedEvent(username));
    }

    @Test
    void minecraftLink() {
        service.minecraftEnroll(username, "Steve");
        service.minecraftSync(username);
        service.minecraftDisconnect(username);

        assertThat(data.getMinecraftLink()).isEqualTo(new LinkStateResponse("NONE", null, null));
        assertThat(events).containsExactly(new MinecraftEnrollEvent(username, "PENDING", "Steve"),
                new MinecraftSyncEvent(username, "ACCEPTED", "Steve"), new MinecraftDisconnectedEvent(username));
    }

    @Test
    void obsSettings_areSavedWithoutEvents() {
        service.saveObsSettings(username, false, "10.0.0.2", 4456, null);

        assertThat(data.getPage(username, null, null).obs()).isEqualTo(new ObsData(false, "10.0.0.2", 4456, true));
        assertThat(events).isEmpty();
    }

    @Test
    void widget_resolvesItsTokenAcrossSessions_andFollowsTheObsToggle() {
        UUID token = data.getWidgetToken();
        UUID owner = data.getChannelDto().id();
        service.saveObsSettings(username, true, "10.0.0.2", 4456, "pw");
        // Read from another (anonymous) request, like an OBS browser source.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(service.widgetAccess(token).body())
                .isEqualTo(new TwitchatWidgetAccessResponse(owner.toString(), true));
        assertThat(service.widgetObsConfig(token))
                .isEqualTo(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "auto"));

        data.saveObsSettings(false, "10.0.0.2", 4456, null);
        assertThat(service.widgetAccess(token).body())
                .isEqualTo(new TwitchatWidgetAccessResponse(owner.toString(), false));
        assertThat(service.widgetObsConfig(token)).isNull();

        UUID unknown = UUID.randomUUID();
        assertThat(service.widgetAccess(unknown).status()).isEqualTo(404);
        assertThat(service.widgetObsConfig(unknown)).isNull();
    }

    @Test
    void regeneratingTheWidget_changesItsToken_andTheOldOneStopsResolving() {
        UUID old = data.getWidgetToken();
        assertThat(service.twitchatSettings(username).widgetToken()).isEqualTo(old.toString());

        var regenerated = service.regenerateTwitchatWidget(username).body();

        assertThat(regenerated.widgetToken()).isNotEqualTo(old.toString());
        assertThat(service.twitchatSettings(username).widgetToken()).isEqualTo(regenerated.widgetToken());
        assertThat(service.widgetAccess(old).status()).isEqualTo(404);
        assertThat(service.widgetAccess(UUID.fromString(regenerated.widgetToken())).status()).isEqualTo(200);
    }

    @Test
    void settings_areSaved_eachSaveTellingTheirWholeState() {
        service.saveCclSettings(username, false, Set.of("Gore"));
        service.saveTwSettings(username, true, Set.of("spiders"));
        service.saveNoGameSettings(username, "1", "Just Chatting", Set.of(), true, true, false);
        service.saveIncompleteFallbackSettings(username, "2", "Other", Set.of("Drugs"));

        assertThat(data.getUserSettings().cclFeatureEnabled()).isFalse();
        assertThat(data.getUserSettings().twFeatureEnabled()).isTrue();
        assertThat(data.getUserSettings().noGameTwitchGameName()).isEqualTo("Just Chatting");
        assertThat(data.getUserSettings().incompleteFallbackCcls()).containsExactly("Drugs");
        assertThat(events).hasSize(4).allSatisfy(e -> assertThat(e).isInstanceOf(fr.enimaloc.catapult.event.ChannelSettingsUpdatedEvent.class));
        var last = ((fr.enimaloc.catapult.event.ChannelSettingsUpdatedEvent) events.getLast()).settings();
        assertThat(last.ccl().blockedCcls()).containsExactly("Gore");
        assertThat(last.tw().enabled()).isTrue();
        assertThat(last.noGame().twitchGameName()).isEqualTo("Just Chatting");
        assertThat(last.incompleteFallback().ccls()).containsExactly("Drugs");
    }

    @Test
    void twitchatEvents_keepCatapultApisLimits() {
        TwitchatEventActionDto payload = new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "RAW", "{\"message\":\"hi\"}", true);

        assertThat(service.saveTwitchatEvent(username, "BOT_ENABLED", java.util.Collections.nCopies(21, payload)))
                .containsExactly(new fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError(20, "action",
                        "At most 20 payloads per event"));
        String huge = "{\"message\":\"" + "x".repeat(33_000) + "\"}";
        assertThat(service.testTwitchatEvent(username, "BOT_ENABLED",
                List.of(payload, new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "RAW", huge, true))))
                .singleElement().satisfies(e -> {
                    assertThat(e.index()).isEqualTo(1);
                    assertThat(e.field()).isEqualTo("dataJson");
                });
        assertThat(service.twitchatEvents(username).get("BOT_ENABLED")).isNullOrEmpty();
    }

    @Test
    void twitchatEvents_areSavedThenReadBack() {
        List<TwitchatEventActionDto> payloads = List.of(new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "RAW", "{\"message\":\"hi\"}", true));

        assertThat(service.saveTwitchatEvent(username, "BOT_ENABLED", payloads)).isEmpty();

        assertThat(service.twitchatEvents(username).get("BOT_ENABLED")).isEqualTo(payloads);
    }

    @Test
    void invalidTwitchatEvent_returnsItsErrors_andIsNotSaved() {
        List<TwitchatEventActionDto> bad = List.of(new TwitchatEventActionDto("NOPE", "RAW", "{}", true));

        assertThat(service.saveTwitchatEvent(username, "BOT_ENABLED", bad)).isNotEmpty();

        assertThat(service.twitchatEvents(username).get("BOT_ENABLED")).isEmpty();
    }

    @Test
    void testTwitchatEvent_publishesTheDispatchOnTheHub() {
        List<TwitchatEventActionDto> payloads = List.of(new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "RAW", "{\"message\":\"hi\"}", true));

        assertThat(service.testTwitchatEvent(username, "BOT_ENABLED", payloads)).isEmpty();

        org.mockito.Mockito.verify(hub).publish(org.mockito.ArgumentMatchers.eq(service.currentUserId()),
                org.mockito.ArgumentMatchers.contains("\"eventType\":\"BOT_ENABLED\""));
    }

    @Test
    void testTwitchatEvent_rendersVariablesAndEveryButton() {
        List<TwitchatEventActionDto> payloads = List.of(new TwitchatEventActionDto("CUSTOM_CHAT_MESSAGE", "RAW",
                "{\"message\":\"{{channel}} joue à {{gameName}}\",\"actions\":[{\"label\":\"x\",\"url\":\"{{action:REVERT_CATEGORY}}\"}]}", true));

        service.testTwitchatEvent(username, "BOT_ENABLED", payloads);

        org.mockito.Mockito.verify(hub).publish(org.mockito.ArgumentMatchers.eq(service.currentUserId()), org.mockito.ArgumentMatchers.argThat(json ->
                json.contains(username + " joue à Jeu de test") && json.contains("mock-revert_category-")));
    }

    @Test
    void dispatchTwitchatEvent_sendsTheEnabledPayloads_withTheEventsActionsOnly() {
        service.saveTwitchatEvent(username, "STREAM_STARTED", List.of(
                new TwitchatEventActionDto("SEND_MESSAGE", "RAW", "{\"message\":\"{{channel}} is live\"}", true),
                new TwitchatEventActionDto("SEND_MESSAGE", "RAW", "{\"message\":\"off\"}", false),
                // REVERT_CATEGORY doesn't apply to a stream start: skipped whole, like catapult-api does.
                new TwitchatEventActionDto("SEND_MESSAGE", "RAW", "{\"message\":\"{{action:REVERT_CATEGORY}}\"}", true)));

        assertThat(service.dispatchTwitchatEvent("STREAM_STARTED", Map.of())).isEqualTo(1);

        org.mockito.Mockito.verify(hub).publish(org.mockito.ArgumentMatchers.eq(service.currentUserId()), org.mockito.ArgumentMatchers.argThat(json ->
                json.contains(username + " is live") && !json.contains("off") && json.contains("\"eventType\":\"STREAM_STARTED\"")));
    }

    @Test
    void dispatchTwitchatEvent_withNothingToSend_publishesNothing() {
        assertThat(service.dispatchTwitchatEvent("NO_GAME_DETECTED", Map.of())).isZero();

        org.mockito.Mockito.verifyNoInteractions(hub);
    }

    @Test
    void toggleBot_sendsItsTwitchatEvent() {
        service.saveTwitchatEvent(username, "BOT_DISABLED", List.of(
                new TwitchatEventActionDto("SEND_MESSAGE", "RAW", "{\"message\":\"bot off\"}", true)));

        service.toggleBot(username);

        org.mockito.Mockito.verify(hub).publish(org.mockito.ArgumentMatchers.eq(service.currentUserId()),
                org.mockito.ArgumentMatchers.contains("\"eventType\":\"BOT_DISABLED\""));
    }

    // --- admin: members ------------------------------------------------------------------------

    /** As catapult-api: an active member other than the admin, never the system account; the token logs in as them. */
    @Test
    void adminImpersonate_mintsATokenForTheMember_refusesSelfSystemAndUnknown() {
        data.addModeratedChannel();
        var members = allMembers();
        var target = members.stream().filter(m -> m.twitchUsername().equals("moderee_1")).findFirst().orElseThrow();
        assertThat(target.systemAccount()).isFalse();

        assertThat(service.adminImpersonate(selfMember().twitchUsername())).isEqualTo(new ApiClient.Result<>(403, null));
        assertThat(service.adminImpersonate("catapult_bot")).isEqualTo(new ApiClient.Result<>(403, null));
        assertThat(service.adminImpersonate("nobody")).isEqualTo(new ApiClient.Result<>(404, null));
        var result = service.adminImpersonate(target.twitchUsername());
        assertThat(result.status()).isEqualTo(200);
        String token = result.body();
        assertThat(token).isNotNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(service.getData().getChannelDto().twitchUsername()).isEqualTo(target.twitchUsername());
        assertThat(ApiClient.currentLogin()).isEqualTo(target.twitchUsername());
        assertThat(service.isAdmin()).isFalse();
    }

    /** As catapult-api: every account that logged in is a member, whichever session the admin looks from. */
    @Test
    void adminMembers_listsTheAccountsOfOtherMockSessions_soTheAdminCanImpersonateThem() {
        String other = username;
        loginAs("1");

        var members = allMembers();
        assertThat(selfMember().twitchUsername()).isEqualTo(username);
        assertThat(members).extracting(m -> m.twitchUsername()).contains(other);
        assertThat(service.adminImpersonate(other).status()).isEqualTo(200);
    }

    /** A deleted member stays deleted, even once their session is used again. */
    @Test
    void adminDeleteMember_isNotUndoneByTheMembersNextRequest() {
        UUID other = service.currentUserId();
        String otherToken = (String) ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                .getRequest().getSession().getAttribute(ApiClient.SESSION_JWT_KEY);
        loginAs("1");
        service.adminDeleteMember(other);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ApiClient.SESSION_JWT_KEY, otherToken);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(allMembers()).noneMatch(m -> m.id().equals(other));
    }

    /** Every member, page after page, as the admin tab would browse them. */
    private java.util.List<fr.enimaloc.catapult.common.dto.admin.AdminMemberDto> allMembers() {
        java.util.List<fr.enimaloc.catapult.common.dto.admin.AdminMemberDto> all = new java.util.ArrayList<>();
        var first = service.adminMembers("", 0);
        all.addAll(first.members());
        for (int page = 1; page < first.totalPages(); page++) all.addAll(service.adminMembers("", page).members());
        return all;
    }

    private fr.enimaloc.catapult.common.dto.admin.AdminMemberDto selfMember() {
        return allMembers().stream().filter(m -> m.id().equals(service.currentUserId())).findFirst().orElseThrow();
    }

    @Test
    void adminMembers_pagesFiftyAtATime_byUsername_namelessLast() {
        var first = service.adminMembers("", 0);
        var last = service.adminMembers("", first.totalPages() - 1);

        assertThat(first.members()).hasSize(50);
        assertThat(first.number()).isZero();
        assertThat(first.totalPages()).isGreaterThan(2);
        assertThat(first.totalElements()).isGreaterThan(120);
        assertThat(first.members()).extracting(m -> m.twitchUsername()).isSorted();
        assertThat(last.members().getLast().twitchUsername()).isNull(); // the inactive account, nameless
        assertThat(allMembers()).filteredOn(m -> m.systemAccount()).hasSize(1);
        assertThat(allMembers()).extracting(m -> m.id()).contains(service.currentUserId());
        assertThat(service.adminMembers("", 99).members()).isEmpty();
    }

    @Test
    void adminMembers_filtersByUsernameSubstring_orExactTwitchId_andSearchesTwentyAtMost() {
        assertThat(service.adminMembers("VIEWER05", 0).members()).extracting(m -> m.twitchUsername())
                .containsExactly("viewer050", "viewer051", "viewer052", "viewer053", "viewer054", "viewer055",
                        "viewer056", "viewer057", "viewer058", "viewer059");
        assertThat(service.adminMembers("5000007", 0).members()).extracting(m -> m.twitchUsername()).containsExactly("viewer007");
        assertThat(service.adminMembers("500000", 0).members()).isEmpty(); // an id is matched whole
        assertThat(service.adminMembers("  ", 0).totalElements()).isGreaterThan(120);

        assertThat(service.adminSearchMembers("viewer")).hasSize(20).extracting(m -> m.twitchUsername()).isSorted();
        assertThat(service.adminSearchMembers("viewer10")).extracting(m -> m.twitchUsername())
                .containsExactly("viewer100", "viewer101", "viewer102", "viewer103", "viewer104", "viewer105",
                        "viewer106", "viewer107", "viewer108", "viewer109");
        assertThat(service.adminSearchMembers("  ")).isEmpty();
        assertThat(service.adminSearchMembers(null)).isEmpty();
    }

    @Test
    void adminMemberActions_changeTheList() {
        java.util.UUID self = service.currentUserId();

        service.adminToggleMemberBot(self);
        service.adminUnlinkMemberSteam(self);
        assertThat(selfMember()).matches(m -> !m.botEnabled() && m.steamId() == null);

        service.adminPromoteMemberToSystem(self);
        assertThat(allMembers()).filteredOn(m -> m.systemAccount())
                .extracting(m -> m.id()).containsExactly(self);

        service.adminUnlinkMemberTwitch(self);
        assertThat(selfMember()).matches(m -> m.twitchId() == null && "INACTIVE".equals(m.status()));
        assertThat(service.adminMembers("", 0).isLive(self)).isFalse();

        service.adminDeleteMember(self);
        assertThat(allMembers()).noneMatch(m -> m.id().equals(self));
    }

    // --- admin: CCL, caches, data ---

    @Test
    void adminCcl_savesACclsMappings() {
        loginAs("{}");
        var first = service.adminCcl().ccls().getFirst();

        assertThat(service.adminSaveCclMappings(first.id(), Set.of(3L))).isEqualTo(204);
        assertThat(service.adminSaveCclMappings("no-such-ccl", Set.of(3L))).isEqualTo(404);
        assertThat(service.adminRefreshCcl()).isEqualTo(204);

        assertThat(service.adminCcl().ccls().getFirst().mappedIds()).isEqualTo("3");
        assertThat(service.adminCcl().igdbDescriptors()).isNotEmpty();
    }

    @Test
    void adminCaches_pageSearchAndEvict() {
        loginAs("{}");
        assertThat(service.adminCaches()).extracting(c -> c.name()).contains("igdb-game-cache", "tw-known-paths");

        var entries = service.adminCacheEntries("igdb-game-cache", null, 0);
        assertThat(entries.content()).hasSize(25);
        assertThat(entries.totalPages()).isEqualTo(2);
        assertThat(service.adminCacheEntries("igdb-game-cache", "", 1).content()).hasSize(5);

        String key = entries.content().getFirst().key();
        assertThat(service.adminCacheEntries("igdb-game-cache", "#1", 0).content()).extracting(e -> e.key()).contains(key);
        assertThat(service.adminCacheEntry("igdb-game-cache", key).expiresInSeconds()).isPositive();
        assertThat(service.adminCacheEntry("chat-command-user-cache",
                service.adminCacheEntries("chat-command-user-cache", null, 0).content().getFirst().key()).detail())
                .isInstanceOf(java.util.List.class);

        assertThat(service.adminDeleteCacheEntry("igdb-game-cache", key)).isEqualTo(204);
        assertThat(service.adminDeleteCacheEntry("igdb-game-cache", key)).isEqualTo(404);
        assertThat(service.adminDeleteCacheEntry("tw-known-paths", "game.name")).isEqualTo(400);
        assertThat(service.adminCacheEntries("nope", null, 0)).isNull();
    }

    @Test
    void adminData_createUpdateDelete() {
        loginAs("{}");
        assertThat(service.adminDataRepos()).extracting(r -> r.name()).containsExactly("chat-command", "igdb-rating-descriptor");

        assertThat(service.adminCreateDataRow("chat-command", Map.of("name", "!hi", "template", "hello"))).isEqualTo(201);
        var created = service.adminDataRows("chat-command", "!hi", 0).content();
        assertThat(created).hasSize(1);
        String id = String.valueOf(created.getFirst().get("id"));

        assertThat(service.adminUpdateDataRow("chat-command", id, Map.of("template", "hey"))).isEqualTo(200);
        assertThat(service.adminDataRow("chat-command", id)).containsEntry("template", "hey").containsKey("aliases");

        assertThat(service.adminDeleteDataRow("chat-command", id)).isEqualTo(204);
        assertThat(service.adminDataRow("chat-command", id)).isNull();
        assertThat(service.adminUpdateDataRow("chat-command", id, Map.of())).isEqualTo(404);
    }

    @Test
    void adminData_refusesToDeleteADescriptorACclStillMaps() {
        loginAs("{}");
        String mapped = String.valueOf(service.adminCcl().ccls().getFirst().igdbMappings().getFirst().id());

        assertThat(service.adminDeleteDataRow("igdb-rating-descriptor", mapped)).isEqualTo(409);
    }

    @Test
    void experiments_areSharedAndEditableThroughTheService() {
        loginAs("exp");
        var list = service.adminExperiments();
        assertThat(list).extracting("key").contains("new-dashboard");
        UUID id = list.stream().filter(e -> e.key().equals("new-dashboard")).findFirst().orElseThrow().id();
        var settings = service.adminExperiment(id);
        UUID variant = settings.variants().get(1).id();
        assertThat(service.adminExperiment(UUID.randomUUID())).isNull();
        assertThat(service.adminFlagKeys()).isNotNull();

        assertThat(service.adminExperimentAction(id, "reassign")).isEqualTo(204);
        assertThat(service.adminExperimentWeightsPreview(id, Map.of(settings.variants().get(0).id(), 50, variant, 50))).isNotNull();
        assertThat(service.adminExperimentWeightsPreview(UUID.randomUUID(), Map.of())).isNull();
        assertThat(service.adminExperimentWeights(id, Map.of(settings.variants().get(0).id(), 50, variant, 50))).isEqualTo(204);

        assertThat(service.adminAddExperimentRule(id,
                new fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest("RANDOM", 1, 50, null, null, null)))
                .isEqualTo(204);
        UUID rule = service.adminExperiment(id).rules().getFirst().id();
        assertThat(service.adminDeleteExperimentRule(id, rule)).isEqualTo(204);

        String login = service.adminMembers("", 0).members().getFirst().twitchUsername();
        assertThat(service.adminAddExperimentOverride(id,
                new fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest("USER", "EXCLUDE", 1, " " + login.toUpperCase() + " ",
                        null, null, null, null))).isEqualTo(204);
        assertThat(service.adminAddExperimentOverride(id,
                new fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest("USER", "EXCLUDE", 1, "nobody-here",
                        null, null, null, null))).isEqualTo(404);
        UUID override = service.adminExperiment(id).overrides().getFirst().id();
        assertThat(service.adminDeleteExperimentOverride(id, override)).isEqualTo(204);

        var assignments = service.adminExperimentAssignments(id, null, "", 0);
        assertThat(assignments).isNotNull();
        assertThat(service.adminExperimentAssignments(UUID.randomUUID(), null, "", 0)).isNull();
        assertThat(service.adminMoveExperimentAssignment(id, UUID.randomUUID(), variant)).isEqualTo(404);
    }

    private static fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody toExperiment(UUID experiment, UUID variant) {
        return new fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody("T", "B",
                fr.enimaloc.catapult.common.dto.notification.Severity.INFO, null, null, null, null,
                fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody.EXPERIMENT, experiment, variant);
    }

    @Test
    void sendNotification_toAnExperimentsParticipants_reportsHowManyWereTold() {
        var dashboard = service.adminExperiments().stream().filter(e -> e.key().equals("new-dashboard")).findFirst().orElseThrow();
        UUID variant = dashboard.variants().get(1).id();
        long all = service.adminNotificationAudienceCount(dashboard.id(), null);
        long one = service.adminNotificationAudienceCount(dashboard.id(), variant);

        var everyone = service.adminSendNotification(toExperiment(dashboard.id(), null));
        var narrowed = service.adminSendNotification(toExperiment(dashboard.id(), variant));

        assertThat(everyone.status()).isEqualTo(201);
        assertThat(everyone.body()).isEqualTo((int) all);
        assertThat(narrowed.body()).isEqualTo((int) one);
        assertThat(one).isPositive().isLessThan(all);
        assertThat(service.adminCreateNotification(toExperiment(dashboard.id(), null))).isEqualTo(201);
    }

    @Test
    void sendNotification_toTheChatCommandsAudience_reportsItsCountLikeTheRealOne() {
        var body = new fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody("T", "B",
                fr.enimaloc.catapult.common.dto.notification.Severity.INFO, null, null, null, null,
                fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody.CHAT_COMMANDS);

        var result = service.adminSendNotification(body);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).isPositive();
    }

    @Test
    void sendNotification_toAnExperiment_refusesAMissingOrUnknownOne() {
        assertThat(service.adminSendNotification(toExperiment(null, null)).status()).isEqualTo(400);
        assertThat(service.adminSendNotification(toExperiment(UUID.randomUUID(), null)).status()).isEqualTo(404);
        assertThat(service.adminNotificationAudienceCount(UUID.randomUUID(), null)).isNull();
    }

    @Test
    void notificationChannels_survivesASessionChange_andIsEmptyByDefault() {
        assertThat(service.notificationChannels().channels()).isEmpty();

        assertThat(service.saveNotificationChannels(Set.of(fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT))).isEqualTo(204);
        assertThat(service.notificationChannels().channels())
                .containsExactly(fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT);

        RequestContextHolder.resetRequestAttributes();
        loginAs("{}");
        // Kept with the account, not the browser session.
        assertThat(service.notificationChannels().channels())
                .containsExactly(fr.enimaloc.catapult.common.dto.notification.NotificationChannel.TWITCHAT);
    }

    @Test
    void notificationChannels_needASession() {
        RequestContextHolder.resetRequestAttributes();

        assertThat(service.notificationChannels()).isNull();
        assertThat(service.saveNotificationChannels(Set.of())).isEqualTo(401);
    }
}
