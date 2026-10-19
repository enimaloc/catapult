package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.binding.BindingDeletedEvent;
import fr.enimaloc.catapult.event.binding.BindingIgnoredStateEvent;
import fr.enimaloc.catapult.event.binding.BindingUpdatedEvent;
import fr.enimaloc.catapult.event.binding.CclStateEvent;
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
    void searchGames_matchesCategoryNamesIgnoringCase() {
        assertThat(service.searchGames(username, "VAL")).isEqualTo(List.of(Map.of("id", "508455", "name", "Valheim")));
        assertThat((List<?>) service.searchGames(username, "")).hasSize(MockPresets.CATEGORIES.size());
        assertThat((List<?>) service.searchGames(username, "zzz")).isEmpty();
    }

    @Test
    void dtddIsInert() {
        assertThat(service.dtddMappingStatus(username)).isEqualTo(new DtddMappingStatusDto(null, null, false, null));
        assertThat(service.dtddSearch("doom")).isEqualTo(new SearchResponse(List.of()));
        service.dtddValidate("1");
        service.dtddPropose("1", 2L, "r");
        assertThat(events).isEmpty();
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
        String login = service.adminMembers().members().getFirst().twitchUsername();

        assertThat(service.adminAddGroupMember(group.id(), "nobody-here")).isEqualTo(404);
        assertThat(service.adminAddGroupMember(java.util.UUID.randomUUID(), login)).isEqualTo(404);
        assertThat(service.adminAddGroupMember(group.id(), " " + login.toUpperCase() + " ")).isEqualTo(204);
        assertThat(service.adminGroups().stream().filter(g -> g.key().equals("crew")).findFirst().orElseThrow().memberCount()).isEqualTo(1);
        assertThat(service.adminDeleteGroup(group.id())).isEqualTo(204);
        assertThat(service.adminDeleteGroup(group.id())).isEqualTo(404);
    }

    @Test
    void targeting_flagsAndGroups_followTheActions() {
        var member = service.adminMembers().members().getFirst();
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
        service.recheckGame(username);
        service.refreshSteamProfileCache(username);

        assertThat(events).isEmpty();
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
    void settings_areSavedWithoutEvents() {
        service.saveCclSettings(username, false, Set.of("Gore"));
        service.saveTwSettings(username, true, Set.of("spiders"));
        service.saveNoGameSettings(username, "1", "Just Chatting", Set.of(), true, true, false);
        service.saveIncompleteFallbackSettings(username, "2", "Other", Set.of("Drugs"));

        assertThat(data.getUserSettings().cclFeatureEnabled()).isFalse();
        assertThat(data.getUserSettings().twFeatureEnabled()).isTrue();
        assertThat(data.getUserSettings().noGameTwitchGameName()).isEqualTo("Just Chatting");
        assertThat(data.getUserSettings().incompleteFallbackCcls()).containsExactly("Drugs");
        assertThat(events).isEmpty();
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
        var members = service.adminMembers().members();
        var target = members.get(1);
        assertThat(target.systemAccount()).isFalse();

        assertThat(service.adminImpersonate(members.getFirst().twitchUsername())).isEqualTo(new ApiClient.Result<>(403, null));
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

    @Test
    void adminMembers_listsTheSessionsChannelFirst_thenTheSystemAccount() {
        var members = service.adminMembers().members();

        assertThat(members.getFirst().id()).isEqualTo(service.currentUserId());
        assertThat(members).filteredOn(m -> m.systemAccount()).hasSize(1);
    }

    @Test
    void adminMemberActions_changeTheList() {
        java.util.UUID self = service.currentUserId();

        service.adminToggleMemberBot(self);
        service.adminUnlinkMemberSteam(self);
        assertThat(service.adminMembers().members().getFirst())
                .matches(m -> !m.botEnabled() && m.steamId() == null);

        service.adminPromoteMemberToSystem(self);
        assertThat(service.adminMembers().members()).filteredOn(m -> m.systemAccount())
                .extracting(m -> m.id()).containsExactly(self);

        service.adminUnlinkMemberTwitch(self);
        assertThat(service.adminMembers().members().getFirst())
                .matches(m -> m.twitchId() == null && "INACTIVE".equals(m.status()));
        assertThat(service.adminMembers().isLive(self)).isFalse();

        service.adminDeleteMember(self);
        assertThat(service.adminMembers().members()).noneMatch(m -> m.id().equals(self));
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
}
