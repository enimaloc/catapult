package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.common.dto.UserInfoResponse;
import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRebalanceDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto;
import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
import fr.enimaloc.catapult.common.dto.admin.TwKeywordTestDto;
import fr.enimaloc.catapult.common.dto.admin.TwRebuild;
import fr.enimaloc.catapult.common.dto.admin.AccountDto;
import fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannelsDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationSnapshotDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.IanaRegistryDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.service.WellKnownContent;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatDispatch;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatPayload;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEvents;
import fr.enimaloc.catapult.common.twitchat.TwitchatCatalog;
import fr.enimaloc.catapult.common.twitchat.TwitchatPayloadRenderer;
import fr.enimaloc.catapult.common.twitchat.TwitchatPayloadValidator;
import fr.enimaloc.catapult.event.BotStateChangedEvent;
import fr.enimaloc.catapult.event.binding.BindingDeletedEvent;
import fr.enimaloc.catapult.event.binding.BindingIgnoredStateEvent;
import fr.enimaloc.catapult.event.binding.BindingUpdatedEvent;
import fr.enimaloc.catapult.event.binding.CclStateEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftDisconnectedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftEnrollEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenDeletedEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.event.ChannelConnectionChangedEvent;
import fr.enimaloc.catapult.event.ChannelSettingsUpdatedEvent;
import fr.enimaloc.catapult.event.DtddMappingChangedEvent;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.dto.changelog.ChangelogSection;
import fr.enimaloc.catapult.event.steam.SteamTokenSavedEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenSharedStateEvent;
import fr.enimaloc.catapult.event.tw.TwEnabledStateEvent;
import fr.enimaloc.catapult.event.tw.TwResetEvent;
import fr.enimaloc.catapult.event.tw.TwUpdatedEvent;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.twitchat.TwitchatDispatchHub;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("mock")
public class MockApiService implements ApiService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** What catapult-api answers an action that went through. */
    private static final int NO_CONTENT = 204;

    /** {@code catapult.mock.degraded=true} shows the degraded banner. */
    @Value("${catapult.mock.degraded:false}")
    private boolean degraded;

    private final ApplicationEventPublisher eventPublisher;
    private final TwitchatDispatchHub twitchatHub;
    private final TwitchatPayloadValidator twitchatValidator = new TwitchatPayloadValidator(TwitchatCatalog.load());

    /**
     * One {@link MockData} per mock connection, keyed by a random token minted in
     * {@link #exchangeCode} — the same token {@link fr.enimaloc.catapult.controller.AuthController}
     * stores under {@link ApiClient#SESSION_JWT_KEY}, so each browser session gets its own
     * isolated mock state instead of every tab sharing one global instance.
     */
    private final Map<String, MockData> data = new ConcurrentHashMap<>();
    private final MockWellKnownStore wellKnown = new MockWellKnownStore();
    /** Application-wide too: every page shows them, logged in or not. */
    private final MockMetaTagStore metaTags = new MockMetaTagStore();
    /** Application-wide, like the well-known files: a notification sent in one session reaches the others. */
    private MockNotificationStore notifications;
    private final MockDtddProposalsStore dtddProposals = new MockDtddProposalsStore();
    /** Application-wide: the groups an admin creates are the same for every mock session. */
    private final MockGroupsStore groups = new MockGroupsStore();
    /** Application-wide: the experiments an admin edits are the same for every mock session. */
    private final MockExperimentsStore experiments = new MockExperimentsStore();
    /** Application-wide: the whitelist and its invitations are the same for every mock session. */
    private final MockWhitelistStore whitelist = new MockWhitelistStore();
    /** Application-wide: the shared Steam and DTDD keys are the same for every mock session. */
    private final MockKeysStore apiKeys = new MockKeysStore();
    /** Application-wide: the trigger warning definitions are the same for every mock session. */
    private final MockTwStore tws = new MockTwStore();
    /** Application-wide: the Minecraft service accounts are the same for every mock session. */
    private final MockMinecraftAccountsStore minecraftAccounts = new MockMinecraftAccountsStore();
    /** Application-wide, as catapult-api's: every mock account that logged in is a member any admin session lists. */
    private final MockMembers members = new MockMembers();
    /** Application-wide too: a member's custom flags are the same for every mock session. */
    private final MockTargetingStore targeting = new MockTargetingStore();
    /** By account, the channels each one also receives notifications through. */
    private final Map<UUID, Set<NotificationChannel>> notificationChannels = new ConcurrentHashMap<>();
    /** By account, as catapult-api's seen_feature: the new features each one has opened. */
    private final Map<UUID, Set<String>> seenFeatures = new ConcurrentHashMap<>();

    /**
     * Every member (the whole list, not a page), the calling session's own account first; that account (and the channels it
     * moderates) is registered here too, for a session rebuilt by {@link #currentData} or given
     * a moderated channel by the mock admin page. Null without a session, as catapult-api's 401.
     */
    private AdminMembersPage memberPage() {
        MockData current = currentData();
        if (current == null) return null;
        members.register(current);
        return members.page(current.getChannelDto().id());
    }

    /** The mock member {@code id}'s login; null when there is no such member or no session. */
    private String mockMemberLogin(UUID id) {
        AdminMembersPage page = memberPage();
        if (page == null) return null;
        return page.members().stream().filter(m -> id.equals(m.id()))
                .map(m -> m.twitchUsername()).findFirst().orElse(null);
    }

    private synchronized MockNotificationStore notifications() {
        if (notifications == null) notifications = new MockNotificationStore(eventPublisher, Clock.systemUTC(), this::mockMemberLogin);
        return notifications;
    }

    /**
     * Resolves the calling session's data the same way {@link ApiClient} resolves its JWT.
     * The token is minted in {@link #exchangeCode} (JWT-shaped, unsigned), not a mock config, so it can't be
     * fed to {@link MockData#fromJwt} — if the entry is missing (e.g. the server restarted and
     * lost its in-memory state while the browser session cookie survived), data is regenerated
     * deterministically from the token via {@link MockData#randomFrom} so it stays the same
     * across repeated requests instead of reshuffling every time it's rebuilt.
     */
    private MockData currentData() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        HttpSession session = attrs.getRequest().getSession(false);
        String token = session != null ? (String) session.getAttribute(ApiClient.SESSION_JWT_KEY) : null;
        if (token != null && !data.containsKey(token)) data.put(token, MockData.randomFrom(token));
        return token != null ? data.get(token) : null;
    }

    /** Exposes the calling session's live instance for {@link fr.enimaloc.catapult.controller.mock.MockAdminController} to read and mutate. */
    public MockData getData() {
        return currentData();
    }

    @Override
    public TokenResponse exchangeCode(String code) {
        log.trace("exchangeCode({})", code);
        // A full JSON config from the mock login form still gets honored, so scenarios stay
        // reproducible; anything else (the digit quick-launch links) gets fresh random data
        // instead of always reusing the same fixed preset.
        MockData mockData = code != null && code.startsWith("{") ? MockData.fromJwt(code) : MockData.random();
        return new TokenResponse(mint(mockData, List.of("ROLE_USER")));
    }

    /**
     * A token for {@code session}'s member, keyed to it: shaped as a JWT (unsigned, its claims as
     * catapult-api's, a random signature keeping each login distinct) so that ApiClient reads its
     * login. Whether the member is an admin stays the session's (the mock admin page changes it).
     */
    private String mint(MockData session, List<String> roles) {
        var channel = session.getChannelDto();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", channel.id().toString());
        claims.put("twitchId", channel.twitchId());
        claims.put("username", channel.twitchUsername());
        claims.put("roles", roles);
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String token = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(JSON.writeValueAsBytes(claims)) + "." + UUID.randomUUID();
        data.put(token, session);
        members.register(session);
        return token;
    }

    /**
     * As catapult-api: a token for an active member other than the admin (404 unknown, 403 for the
     * admin themself, an inactive or a system account; 401 without a session), keyed to the member's
     * own mock session.
     */
    @Override
    public ApiClient.Result<String> adminImpersonate(String username) {
        log.trace("adminImpersonate({})", username);
        MockData current = currentData();
        if (current == null) return new ApiClient.Result<>(401, null);
        UUID self = current.getChannelDto().id();
        AdminMemberDto target = memberPage().members().stream()
                .filter(m -> username != null && username.equals(m.twitchUsername())).findFirst().orElse(null);
        if (target == null) return new ApiClient.Result<>(404, null);
        if (target.twitchId() == null || target.systemAccount() || target.id().equals(self) || !target.isActive()) {
            return new ApiClient.Result<>(403, null);
        }
        return new ApiClient.Result<>(200, mint(MockData.impersonating(target), List.of("ROLE_USER", "ROLE_PREVIOUS_ADMINISTRATOR")));
    }

    @Override
    public ChannelListResponse channelList() {
        log.trace("channelList()");
        return currentData().getChannelList();
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        log.trace("channelPage({}, {}, {}, {})", username, page, status, source);
        MockData current = currentData();
        return current != null ? current.getPage(username, status, source) : null; // a visitor: catapult-api's 401
    }

    @Override
    public int toggleBot(String username) {
        log.trace("toggleBot({})", username);
        MockData data = currentData();
        data.setBotEnabled(!data.isBotEnabled());
        eventPublisher.publishEvent(new BotStateChangedEvent(username, data.isBotEnabled()));
        dispatchTwitchatEvent(data.isBotEnabled() ? "BOT_ENABLED" : "BOT_DISABLED", Map.of());
        return NO_CONTENT;
    }

    @Override
    public int recheckGame(String username) {
        log.trace("recheckGame({})", username);
        // Nothing to detect in mock mode: the dashboards are told the game the mock has, as catapult-api would.
        GameDto game = currentData().getGameDto();
        eventPublisher.publishEvent(game == null ? new GameChangedEvent(username, null, null, null)
                : new GameChangedEvent(username, game.bindingId(), game.sourceType(), game.sourceName()));
        return NO_CONTENT;
    }

    @Override
    public int cclToggle(String username, String bindingId, boolean enabled) {
        log.trace("cclToggle({}, {}, {})", username, bindingId, enabled);
        currentData().setBindingCclEnabled(bindingId, enabled);
        eventPublisher.publishEvent(new CclStateEvent(username, bindingId, enabled));
        return NO_CONTENT;
    }

    @Override
    public int ignoredToggle(String username, String bindingId, boolean ignored) {
        log.trace("ignoredToggle({}, {}, {})", username, bindingId, ignored);
        currentData().setBindingIgnored(bindingId, ignored);
        eventPublisher.publishEvent(new BindingIgnoredStateEvent(username, bindingId, ignored));
        return NO_CONTENT;
    }

    @Override
    public int deleteBinding(String username, String bindingId) {
        log.trace("deleteBinding({}, {})", username, bindingId);
        currentData().deleteBinding(bindingId);
        eventPublisher.publishEvent(new BindingDeletedEvent(username, bindingId));
        return NO_CONTENT;
    }

    @Override
    public Object searchGames(String username, String q) {
        log.trace("searchGames({}, {})", username, q);
        return MockPresets.CATEGORIES.stream()
                .filter(dto -> dto.name().toLowerCase().contains(q.toLowerCase()))
                .map(dto -> Map.of("id", dto.id(), "name", dto.name()))
                .limit(10)
                .toList();
    }

    @Override
    public int updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, Set<String> ccls) {
        log.trace("updateBinding({}, {}, {}, {}, {})", username, bindingId, twitchGameId, twitchGameName, ccls);
        currentData().updateBindingGame(bindingId, twitchGameId, twitchGameName, ccls);
        eventPublisher.publishEvent(new BindingUpdatedEvent(username, bindingId, twitchGameId, twitchGameName, ccls));
        return NO_CONTENT;
    }

    @Override
    public int saveTws(String bindingId, Set<String> tws) {
        log.trace("saveTws({}, {})", bindingId, tws);
        MockData data = currentData();
        data.setBindingTws(bindingId, tws);
        eventPublisher.publishEvent(new TwUpdatedEvent(data.getChannelDto().twitchUsername(), bindingId, tws));
        return NO_CONTENT;
    }

    @Override
    public int resetTws(String bindingId) {
        log.trace("resetTws({})", bindingId);
        MockData data = currentData();
        data.resetBindingTws(bindingId);
        eventPublisher.publishEvent(new TwResetEvent(data.getChannelDto().twitchUsername(), bindingId));
        return NO_CONTENT;
    }

    @Override
    public int toggleTwEnabled(String bindingId, boolean enabled) {
        log.trace("toggleTwEnabled({}, {})", bindingId, enabled);
        MockData data = currentData();
        data.setBindingTwEnabled(bindingId, enabled);
        eventPublisher.publishEvent(new TwEnabledStateEvent(data.getChannelDto().twitchUsername(), bindingId, enabled));
        return NO_CONTENT;
    }

    @Override
    public int saveSteamToken(String username, String token, boolean shared) {
        log.trace("saveSteamToken({}, {}, {})", username, token, shared);
        currentData().saveSteamToken(shared);
        eventPublisher.publishEvent(new SteamTokenSavedEvent(username, shared));
        return NO_CONTENT;
    }

    @Override
    public int steamTokenSharing(String username, boolean shared) {
        log.trace("steamTokenSharing({}, {})", username, shared);
        currentData().setSteamTokenShared(shared);
        eventPublisher.publishEvent(new SteamTokenSharedStateEvent(username, shared));
        return NO_CONTENT;
    }

    @Override
    public int deleteSteamToken(String username) {
        log.trace("deleteSteamToken({})", username);
        currentData().deleteSteamToken();
        eventPublisher.publishEvent(new SteamTokenDeletedEvent(username));
        return NO_CONTENT;
    }

    @Override
    public int refreshSteamProfileCache(String username) {
        log.trace("refreshSteamProfileCache({})", username);
        // no-op in mock mode
        return NO_CONTENT;
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        log.trace("minecraftStatus({})", username);
        return currentData().getMinecraftLink();
    }

    @Override
    public int minecraftEnroll(String username, String name) {
        log.trace("minecraftEnroll({}, {})", username, name);
        MockData data = currentData();
        data.minecraftEnroll(name);
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftEnrollEvent(username, link.status(), link.minecraftName()));
        return NO_CONTENT;
    }

    @Override
    public int minecraftSync(String username) {
        log.trace("minecraftSync({})", username);
        MockData data = currentData();
        data.minecraftSync();
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftSyncEvent(username, link.status(), link.minecraftName()));
        return NO_CONTENT;
    }

    @Override
    public int minecraftDisconnect(String username) {
        log.trace("minecraftDisconnect({})", username);
        currentData().minecraftDisconnect();
        eventPublisher.publishEvent(new MinecraftDisconnectedEvent(username));
        return NO_CONTENT;
    }

    @Override
    public int xboxDisconnect(String username) {
        log.trace("xboxDisconnect({})", username);
        currentData().xboxDisconnect();
        eventPublisher.publishEvent(new ChannelConnectionChangedEvent(username, "XBOX", false));
        return NO_CONTENT;
    }

    /**
     * Links the member's Xbox account, standing in for Microsoft's login (MockAuthController); null
     * without a session, else the member's channel login.
     */
    public String xboxConnect() {
        log.trace("xboxConnect()");
        MockData current = currentData();
        if (current == null) return null;
        current.xboxConnect();
        String username = current.getChannelDto().twitchUsername();
        eventPublisher.publishEvent(new ChannelConnectionChangedEvent(username, "XBOX", true));
        return username;
    }

    @Override
    public TwitchatWidgetConfig obsConnection() {
        log.trace("obsConnection()");
        MockData current = currentData();
        return current == null ? null : current.obsConnection();
    }

    @Override
    public TwitchatWidgetConfig widgetObsConfig(UUID token) {
        log.trace("widgetObsConfig({})", token);
        // Like catapult-api's /api/twitchat/widget/{token}: the owner's connection, on their branch.
        return widgetOwner(token).map(MockData::obsConnection).orElse(null);
    }

    @Override
    public ApiClient.Result<TwitchatWidgetAccessResponse> widgetAccess(UUID token) {
        log.trace("widgetAccess({})", token);
        return widgetOwner(token)
                .map(d -> new ApiClient.Result<>(200, new TwitchatWidgetAccessResponse(d.getChannelDto().id().toString(), d.obsConnection() != null)))
                .orElseGet(() -> new ApiClient.Result<>(404, null));
    }

    /** The session whose widget link {@code token} is: an OBS browser source has no session of its own. */
    private Optional<MockData> widgetOwner(UUID token) {
        return data.values().stream().filter(d -> token.equals(d.getWidgetToken())).findFirst();
    }

    @Override
    public TwitchatSettingsResponse twitchatSettings(String username) {
        log.trace("twitchatSettings({})", username);
        return currentData().twitchatSettings();
    }

    @Override
    public ApiClient.Result<TwitchatSettingsResponse> regenerateTwitchatWidget(String username) {
        log.trace("regenerateTwitchatWidget({})", username);
        MockData current = currentData();
        current.regenerateWidgetToken();
        return new ApiClient.Result<>(200, current.twitchatSettings());
    }

    @Override
    public int saveObsSettings(String username, boolean enabled, String host, Integer port, String password) {
        log.trace("saveObsSettings({}, {}, {}, {})", username, enabled, host, port);
        currentData().saveObsSettings(enabled, host, port, password);
        return NO_CONTENT;
    }

    @Override
    public int saveTwitchatBranch(String username, String branch) {
        log.trace("saveTwitchatBranch({}, {})", username, branch);
        currentData().saveTwitchatBranch(branch);
        return NO_CONTENT;
    }

    @Override
    public int saveCclSettings(String username, boolean enabled, Set<String> blockedCcls) {
        log.trace("saveCclSettings({}, {}, {})", username, enabled, blockedCcls);
        currentData().saveCclSettings(enabled, blockedCcls);
        publishSettings(username);
        return NO_CONTENT;
    }

    @Override
    public int saveTwSettings(String username, boolean enabled, Set<String> blockedTws) {
        log.trace("saveTwSettings({}, {}, {})", username, enabled, blockedTws);
        currentData().saveTwSettings(enabled, blockedTws);
        publishSettings(username);
        return NO_CONTENT;
    }

    /** The settings cards' whole state, as ChannelEventRedisBridge relays catapult-api's settings.updated. */
    private void publishSettings(String username) {
        UserSettingsDto s = currentData().getUserSettings();
        eventPublisher.publishEvent(new ChannelSettingsUpdatedEvent(username, new ChannelEvents.Settings(
                new ChannelEvents.Settings.Ccl(s.cclFeatureEnabled(), s.blockedCcls()),
                new ChannelEvents.Settings.Tw(s.twFeatureEnabled(), s.blockedTws()),
                new ChannelEvents.Settings.NoGame(s.noGameTwitchGameId(), s.noGameTwitchGameName(), s.noGameCcls(),
                        s.applyDefaultOnStreamStart(), s.applyDefaultOnNoGame(), s.applyDefaultOnStreamEnd()),
                new ChannelEvents.Settings.IncompleteFallback(s.incompleteFallbackTwitchGameId(),
                        s.incompleteFallbackTwitchGameName(), s.incompleteFallbackCcls()),
                new ChannelEvents.Settings.Raid(s.raidRestoreGameMinutes()))));
    }

    @Override
    public UserSettingsDto channelSettings(String username) {
        log.trace("channelSettings({})", username);
        return currentData().getUserSettings();
    }

    @Override
    public BindingDto currentBinding(String username) {
        log.trace("currentBinding({})", username);
        MockData data = currentData();
        GameDto game = data == null ? null : data.getGameDto();
        return game == null || game.bindingId() == null ? null : data.bindingById(game.bindingId());
    }

    @Override
    public List<fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto> enabledChatCommands() {
        log.trace("enabledChatCommands()");
        return List.of(
                new fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto("!game", "VIEWERS", "game"),
                new fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto("!setgame", "MODERATOR", "setgame"),
                new fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto("!triggers", "SUBS", "triggers"),
                new fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto("!release", "VIEWERS", "release"));
    }

    @Override
    public int saveNoGameSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        log.trace("saveNoGameSettings({}, {}, {}, {}, {}, {}, {})", username, twitchGameId, twitchGameName,
                ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
        currentData().saveNoGameSettings(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
        publishSettings(username);
        return NO_CONTENT;
    }

    /** Validated like catapult-api: 1..120 minutes, null or 0 for off, else 400. */
    @Override
    public int saveRaidSettings(String username, Integer restoreGameMinutes) {
        log.trace("saveRaidSettings({}, {})", username, restoreGameMinutes);
        if (restoreGameMinutes != null && restoreGameMinutes != 0 && (restoreGameMinutes < 1 || restoreGameMinutes > 120)) {
            return 400;
        }
        currentData().saveRaidSettings(restoreGameMinutes == null || restoreGameMinutes == 0 ? null : restoreGameMinutes);
        publishSettings(username);
        return NO_CONTENT;
    }

    @Override
    public int saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls) {
        log.trace("saveIncompleteFallbackSettings({}, {}, {}, {})", username, twitchGameId, twitchGameName, ccls);
        currentData().saveIncompleteFallbackSettings(twitchGameId, twitchGameName, ccls);
        publishSettings(username);
        return NO_CONTENT;
    }

    @Override
    public DtddMappingStatusDto dtddMappingStatus(String username) {
        log.trace("dtddMappingStatus({})", username);
        return new DtddMappingStatusDto(null, null, false, null);
    }

    @Override
    public SearchResponse dtddSearch(String q) {
        log.trace("dtddSearch({})", q);
        return new SearchResponse(List.of());
    }

    @Override
    public int dtddValidate(String igdbId) {
        log.trace("dtddValidate({})", igdbId);
        // no-op in mock mode
        return NO_CONTENT;
    }

    @Override
    public int dtddPropose(String igdbId, Long dtddId, String reason) {
        log.trace("dtddPropose({}, {}, {})", igdbId, dtddId, reason);
        MockData current = currentData();
        dtddProposals.propose(igdbId, dtddId, reason, current == null ? null : current.getChannelDto().twitchUsername());
        if (current != null) eventPublisher.publishEvent(new DtddMappingChangedEvent(current.getChannelDto().twitchUsername()));
        return NO_CONTENT;
    }

    @Override
    public List<DtddProposalDto> adminDtddProposals(String status) {
        log.trace("adminDtddProposals({})", status);
        return dtddProposals.list(status);
    }

    @Override
    public List<GroupSummaryDto> adminGroups() {
        log.trace("adminGroups()");
        return groups.list();
    }

    @Override
    public int adminCreateGroup(String key, String name, String description) {
        log.trace("adminCreateGroup({}, {})", key, name);
        return groups.create(key, name, description);
    }

    @Override
    public int adminRenameGroup(UUID id, String name, String description) {
        log.trace("adminRenameGroup({})", id);
        return groups.rename(id, name, description);
    }

    @Override
    public int adminDeleteGroup(UUID id) {
        log.trace("adminDeleteGroup({})", id);
        return groups.delete(id);
    }

    @Override
    public int adminAddGroupMember(UUID id, String twitchUsername) {
        log.trace("adminAddGroupMember({}, {})", id, twitchUsername);
        AdminMembersPage page = memberPage();
        String wanted = twitchUsername == null ? "" : twitchUsername.trim();
        UUID memberId = page == null ? null : page.members().stream()
                .filter(m -> m.twitchUsername() != null && m.twitchUsername().equalsIgnoreCase(wanted))
                .map(m -> m.id()).findFirst().orElse(null);
        return memberId == null ? 404 : groups.addMember(id, memberId);
    }

    @Override
    public List<ExperimentSummaryDto> adminExperiments() {
        log.trace("adminExperiments()");
        return experiments.list();
    }

    @Override
    public ExperimentSettingsDto adminExperiment(UUID id) {
        log.trace("adminExperiment({})", id);
        return experiments.find(id).orElse(null);
    }

    @Override
    public List<FlagKeyDto> adminFlagKeys() {
        log.trace("adminFlagKeys()");
        return targeting.flagKeys();
    }

    @Override
    public int adminExperimentAction(UUID id, String action) {
        log.trace("adminExperimentAction({}, {})", id, action);
        return experiments.action(id, action);
    }

    @Override
    public ExperimentRebalanceDto adminExperimentWeightsPreview(UUID id, Map<UUID, Integer> weights) {
        log.trace("adminExperimentWeightsPreview({})", id);
        return experiments.previewWeights(id, weights).orElse(null);
    }

    @Override
    public int adminExperimentWeights(UUID id, Map<UUID, Integer> weights) {
        log.trace("adminExperimentWeights({})", id);
        return experiments.setWeights(id, weights);
    }

    @Override
    public int adminAddExperimentRule(UUID id, ExperimentRuleRequest rule) {
        log.trace("adminAddExperimentRule({})", id);
        return experiments.addRule(id, rule);
    }

    @Override
    public int adminDeleteExperimentRule(UUID id, UUID ruleId) {
        log.trace("adminDeleteExperimentRule({}, {})", id, ruleId);
        return experiments.deleteRule(id, ruleId);
    }

    @Override
    public int adminAddExperimentOverride(UUID id, ExperimentOverrideRequest override) {
        log.trace("adminAddExperimentOverride({})", id);
        boolean userKnown = false;
        if ("USER".equals(override.overrideType())) {
            AdminMembersPage page = memberPage();
            String wanted = override.twitchUsername() == null ? "" : override.twitchUsername().trim();
            userKnown = page != null && page.members().stream()
                    .anyMatch(m -> m.twitchUsername() != null && m.twitchUsername().equalsIgnoreCase(wanted));
        }
        return experiments.addOverride(id, override, userKnown);
    }

    @Override
    public int adminDeleteExperimentOverride(UUID id, UUID overrideId) {
        log.trace("adminDeleteExperimentOverride({}, {})", id, overrideId);
        return experiments.deleteOverride(id, overrideId);
    }

    @Override
    public ExperimentAssignmentsDto adminExperimentAssignments(UUID id, UUID variantId, String q, int page) {
        log.trace("adminExperimentAssignments({}, {}, {}, {})", id, variantId, q, page);
        return experiments.assignments(id, variantId, q, page).orElse(null);
    }

    @Override
    public int adminMoveExperimentAssignment(UUID id, UUID userId, UUID variantId) {
        log.trace("adminMoveExperimentAssignment({}, {}, {})", id, userId, variantId);
        return experiments.move(id, userId, variantId);
    }

    @Override
    public WhitelistPageDto adminWhitelist() {
        log.trace("adminWhitelist()");
        return whitelist.page();
    }

    @Override
    public int adminToggleWhitelist() {
        log.trace("adminToggleWhitelist()");
        return whitelist.toggle();
    }

    @Override
    public int adminAddWhitelist(String twitchId) {
        log.trace("adminAddWhitelist({})", twitchId);
        return whitelist.add(twitchId);
    }

    @Override
    public int adminDeleteWhitelist(String twitchId) {
        log.trace("adminDeleteWhitelist({})", twitchId);
        return whitelist.delete(twitchId);
    }

    @Override
    public int adminToggleInvites() {
        log.trace("adminToggleInvites()");
        return whitelist.toggleInvites();
    }

    @Override
    public int adminSaveInviteSettings(Integer globalMaxMembers, Integer defaultMaxUses, boolean defaultCanReinvite) {
        log.trace("adminSaveInviteSettings({}, {}, {})", globalMaxMembers, defaultMaxUses, defaultCanReinvite);
        return whitelist.saveSettings(globalMaxMembers, defaultMaxUses, defaultCanReinvite);
    }

    @Override
    public int adminSaveInviteQuota(UUID inviteId, Integer maxUses, Boolean canReinvite) {
        log.trace("adminSaveInviteQuota({}, {}, {})", inviteId, maxUses, canReinvite);
        return whitelist.saveQuota(inviteId, maxUses, canReinvite);
    }

    private boolean mockMemberExists(UUID id) {
        AdminMembersPage page = memberPage();
        return page != null && page.members().stream().anyMatch(m -> id.equals(m.id()));
    }

    @Override
    public AdminMemberSummaryDto adminMember(UUID id) {
        log.trace("adminMember({})", id);
        AdminMembersPage page = memberPage();
        if (page == null) return null;
        return page.members().stream().filter(m -> id.equals(m.id()))
                .map(m -> new AdminMemberSummaryDto(m.id(), m.twitchUsername())).findFirst().orElse(null);
    }

    @Override
    public MemberTargetingDto adminMemberTargeting(UUID id) {
        log.trace("adminMemberTargeting({})", id);
        return mockMemberExists(id) ? new MemberTargetingDto(targeting.flagsOf(id), groups.keysOf(id)) : null;
    }

    @Override
    public int adminSetMemberFlag(UUID id, String key, String value) {
        log.trace("adminSetMemberFlag({}, {})", id, key);
        return mockMemberExists(id) ? targeting.setFlag(id, key, value) : 404;
    }

    @Override
    public int adminDeleteMemberFlag(UUID id, String key) {
        log.trace("adminDeleteMemberFlag({}, {})", id, key);
        return mockMemberExists(id) ? targeting.deleteFlag(id, key) : 404;
    }

    @Override
    public int adminAddMemberToGroup(UUID id, String groupKey) {
        log.trace("adminAddMemberToGroup({}, {})", id, groupKey);
        if (!mockMemberExists(id)) return 404;
        return groups.find(groupKey).map(g -> groups.addMember(g.id(), id)).orElse(404);
    }

    @Override
    public int adminRemoveMemberFromGroup(UUID id, UUID groupId) {
        log.trace("adminRemoveMemberFromGroup({}, {})", id, groupId);
        return groups.removeMember(groupId, id);
    }

    @Override
    public int adminApproveDtddProposal(UUID id) {
        log.trace("adminApproveDtddProposal({})", id);
        return dtddProposals.approve(id);
    }

    @Override
    public int adminRejectDtddProposal(UUID id, String reason) {
        log.trace("adminRejectDtddProposal({})", id);
        return dtddProposals.reject(id);
    }

    @Override
    public UUID currentUserId() {
        MockData current = currentData();
        return current == null ? null : current.getChannelDto().id();
    }

    @Override
    public boolean isAdmin() {
        MockData current = currentData();
        return current != null && current.isAdmin();
    }

    @Override
    public AdminMembersPage adminMembers(String q, int page) {
        log.trace("adminMembers({}, {})", q, page);
        MockData current = currentData();
        if (current == null) return null;
        members.register(current);
        return members.page(q, page);
    }

    @Override
    public List<AdminMemberDto> adminSearchMembers(String q) {
        log.trace("adminSearchMembers({})", q);
        MockData current = currentData();
        if (current == null) return null;
        members.register(current);
        return members.search(q);
    }

    @Override
    public int adminToggleMemberBot(UUID memberId) {
        log.trace("adminToggleMemberBot({})", memberId);
        members.toggleBot(memberId);
        return NO_CONTENT;
    }

    @Override
    public int adminUnlinkMemberSteam(UUID memberId) {
        log.trace("adminUnlinkMemberSteam({})", memberId);
        members.unlinkSteam(memberId);
        return NO_CONTENT;
    }

    @Override
    public int adminUnlinkMemberTwitch(UUID memberId) {
        log.trace("adminUnlinkMemberTwitch({})", memberId);
        members.unlinkTwitch(memberId);
        return NO_CONTENT;
    }

    @Override
    public int adminPromoteMemberToSystem(UUID memberId) {
        log.trace("adminPromoteMemberToSystem({})", memberId);
        members.promoteToSystem(memberId);
        return NO_CONTENT;
    }

    @Override
    public int adminDeleteMember(UUID memberId) {
        log.trace("adminDeleteMember({})", memberId);
        members.delete(memberId);
        return NO_CONTENT;
    }

    @Override
    public int adminMigrateMember(UUID memberId, UUID targetId, boolean settings, boolean getters, boolean bindings) {
        log.trace("adminMigrateMember({}, {}, {}, {}, {})", memberId, targetId, settings, getters, bindings);
        // no-op in mock mode: a mock session holds a single channel's data
        return NO_CONTENT;
    }

    @Override
    public AdminCclPage adminCcl() {
        log.trace("adminCcl()");
        return currentData().getAdminData().ccl();
    }

    @Override
    public int adminRefreshCcl() {
        log.trace("adminRefreshCcl()");
        // no-op in mock mode: there is no Twitch to reload the CCLs from
        return 204;
    }

    @Override
    public int adminSaveCclMappings(String cclId, Set<Long> igdbDescriptorIds) {
        log.trace("adminSaveCclMappings({}, {})", cclId, igdbDescriptorIds);
        return currentData().getAdminData().saveCclMappings(cclId, igdbDescriptorIds);
    }

    @Override
    public List<CacheSummaryDto> adminCaches() {
        log.trace("adminCaches()");
        return currentData().getAdminData().caches();
    }

    @Override
    public AdminPage<CacheEntryDto> adminCacheEntries(String name, String q, int page) {
        log.trace("adminCacheEntries({}, {}, {})", name, q, page);
        return currentData().getAdminData().cacheEntries(name, q, page);
    }

    @Override
    public CacheEntryDetailDto adminCacheEntry(String name, String key) {
        log.trace("adminCacheEntry({}, {})", name, key);
        return currentData().getAdminData().cacheEntry(name, key);
    }

    @Override
    public int adminDeleteCacheEntry(String name, String key) {
        log.trace("adminDeleteCacheEntry({}, {})", name, key);
        return currentData().getAdminData().deleteCacheEntry(name, key);
    }

    @Override
    public List<RepoSummaryDto> adminDataRepos() {
        log.trace("adminDataRepos()");
        return currentData().getAdminData().repos();
    }

    @Override
    public AdminPage<Map<String, Object>> adminDataRows(String repo, String q, int page) {
        log.trace("adminDataRows({}, {}, {})", repo, q, page);
        return currentData().getAdminData().rows(repo, q, page);
    }

    @Override
    public Map<String, Object> adminDataRow(String repo, String id) {
        log.trace("adminDataRow({}, {})", repo, id);
        return currentData().getAdminData().row(repo, id);
    }

    @Override
    public int adminCreateDataRow(String repo, Map<String, String> fields) {
        log.trace("adminCreateDataRow({}, {})", repo, fields);
        return currentData().getAdminData().create(repo, fields);
    }

    @Override
    public int adminUpdateDataRow(String repo, String id, Map<String, String> fields) {
        log.trace("adminUpdateDataRow({}, {}, {})", repo, id, fields);
        return currentData().getAdminData().update(repo, id, fields);
    }

    @Override
    public int adminDeleteDataRow(String repo, String id) {
        log.trace("adminDeleteDataRow({}, {})", repo, id);
        return currentData().getAdminData().delete(repo, id);
    }

    @Override
    public WellKnownContent wellKnown(WellKnownLocation location, String path) {
        log.trace("wellKnown({}, {})", location, path);
        return wellKnown.serve(location, path);
    }

    @Override
    public String adminWellKnownSchema(String path) {
        log.trace("adminWellKnownSchema({})", path);
        return wellKnown.schema(path);
    }

    @Override
    public List<WellKnownFileDto> adminWellKnownFiles() {
        log.trace("adminWellKnownFiles()");
        return wellKnown.list();
    }

    @Override
    public WellKnownFileDetailDto adminWellKnownFile(long id) {
        log.trace("adminWellKnownFile({})", id);
        return wellKnown.get(id);
    }

    @Override
    public IanaRegistryDto adminIanaRegistry() {
        log.trace("adminIanaRegistry()");
        return wellKnown.registry();
    }

    @Override
    public int adminCreateWellKnownFile(WellKnownFileRequest request) {
        log.trace("adminCreateWellKnownFile({})", request);
        return wellKnown.create(request);
    }

    @Override
    public int adminUpdateWellKnownFile(long id, WellKnownFileRequest request) {
        log.trace("adminUpdateWellKnownFile({}, {})", id, request);
        return wellKnown.update(id, request);
    }

    @Override
    public int adminDeleteWellKnownFile(long id) {
        log.trace("adminDeleteWellKnownFile({})", id);
        return wellKnown.delete(id);
    }

    @Override
    public List<MetaTagDto> metaTags() {
        log.trace("metaTags()");
        return metaTags.list();
    }

    @Override
    public int adminCreateMetaTag(MetaTagRequest request) {
        log.trace("adminCreateMetaTag({})", request);
        return metaTags.create(request);
    }

    @Override
    public int adminUpdateMetaTag(long id, MetaTagRequest request) {
        log.trace("adminUpdateMetaTag({}, {})", id, request);
        return metaTags.update(id, request);
    }

    @Override
    public int adminDeleteMetaTag(long id) {
        log.trace("adminDeleteMetaTag({})", id);
        return metaTags.delete(id);
    }

    @Override
    public NotificationSnapshotDto notificationSnapshot() {
        log.trace("notificationSnapshot()");
        UUID account = currentUserId();
        return account == null ? null : notifications().snapshot(account);
    }

    @Override
    public AdminPage<NotificationDto> notifications(int page) {
        log.trace("notifications({})", page);
        UUID account = currentUserId();
        return account == null ? null : notifications().page(account, page);
    }

    @Override
    public int markNotificationRead(UUID id) {
        log.trace("markNotificationRead({})", id);
        UUID account = currentUserId();
        return account == null ? 401 : notifications().markRead(account, id);
    }

    @Override
    public int markAllNotificationsRead() {
        log.trace("markAllNotificationsRead()");
        UUID account = currentUserId();
        return account == null ? 401 : notifications().markAllRead(account);
    }

    @Override
    public NotificationChannelsDto notificationChannels() {
        log.trace("notificationChannels()");
        UUID account = currentUserId();
        return account == null ? null : new NotificationChannelsDto(notificationChannels.getOrDefault(account, Set.of()));
    }

    @Override
    public int saveNotificationChannels(Set<NotificationChannel> channels) {
        log.trace("saveNotificationChannels({})", channels);
        UUID account = currentUserId();
        if (account == null) return 401;
        notificationChannels.put(account, new NotificationChannelsDto(channels).channels());
        return 204;
    }

    @Override
    public List<String> seenFeatures() {
        log.trace("seenFeatures()");
        UUID account = currentUserId();
        return account == null ? null : List.copyOf(seenFeatures.getOrDefault(account, Set.of()));
    }

    @Override
    public int markFeatureSeen(String key) {
        log.trace("markFeatureSeen({})", key);
        UUID account = currentUserId();
        if (account == null) return 401;
        seenFeatures.computeIfAbsent(account, a -> ConcurrentHashMap.newKeySet()).add(key);
        return 204;
    }

    @Override
    public AdminPage<AdminNotificationDto> adminNotifications(int page) {
        log.trace("adminNotifications({})", page);
        return notifications().adminPage(page);
    }

    @Override
    public ApiClient.Result<Integer> adminSendNotification(AdminNotificationCreateBody body) {
        log.trace("adminSendNotification({})", body);
        UUID creator = currentUserId();
        // The creator's bell exists before an "everyone" notification goes out, so it reaches them too.
        if (creator != null) notifications().snapshot(creator);
        if (AdminNotificationCreateBody.EXPERIMENT.equals(body.audience())) {
            if (body.experimentId() == null) return new ApiClient.Result<>(400, null);
            Optional<List<UUID>> participants = experiments.participantIds(body.experimentId(), body.variantId());
            if (participants.isEmpty()) return new ApiClient.Result<>(404, null);
            List<UUID> recipients = participants.get();
            if (recipients.isEmpty()) return new ApiClient.Result<>(201, 0);
            int status = notifications().createShared(body.title(), body.body(), body.severity(), body.ctaUrl(),
                    body.ctaLabel(), body.expiresAt(), recipients);
            return new ApiClient.Result<>(status, status == 201 ? recipients.size() : null);
        }
        int status = notifications().create(body.title(), body.body(), body.severity(), body.ctaUrl(),
                body.ctaLabel(), body.expiresAt(), body.targetUserId());
        // the mock has no chat commands: everyone it has seen stands for the streamers told
        boolean counted = AdminNotificationCreateBody.CHAT_COMMANDS.equals(body.audience()) && status == 201;
        return new ApiClient.Result<>(status, counted ? notifications().accountCount() : null);
    }

    @Override
    public Long adminNotificationAudienceCount(UUID experimentId, UUID variantId) {
        log.trace("adminNotificationAudienceCount({}, {})", experimentId, variantId);
        return experiments.participantIds(experimentId, variantId).map(ids -> (long) ids.size()).orElse(null);
    }

    @Override
    public int adminDeleteNotification(UUID id) {
        log.trace("adminDeleteNotification({})", id);
        return notifications().delete(id);
    }

    @Override
    public ApiClient.Result<String> adminPreviewNotification(String body) {
        log.trace("adminPreviewNotification()");
        return body == null || body.isBlank()
                ? new ApiClient.Result<>(400, null)
                : new ApiClient.Result<>(200, notifications().preview(body));
    }

    @Override
    public int adminBroadcast(BroadcastRequestDto body) {
        log.trace("adminBroadcast({})", body);
        return notifications().broadcast(body);
    }

    @Override
    public Map<String, List<TwitchatEventActionDto>> twitchatEvents(String username) {
        log.trace("twitchatEvents({})", username);
        return currentData().getTwitchatEvents();
    }

    /** catapult-api's own limits on an event's payloads (TwitchatEventActionService). */
    private static final int MAX_TWITCHAT_PAYLOADS = 20;
    private static final int MAX_TWITCHAT_DATA_JSON = 32_768;

    /** As catapult-api checks them: the number of payloads, their size, then the payloads themselves. */
    private List<TwitchatValidationError> validateTwitchat(List<TwitchatEventActionDto> payloads) {
        if (payloads.size() > MAX_TWITCHAT_PAYLOADS) {
            return List.of(new TwitchatValidationError(MAX_TWITCHAT_PAYLOADS, "action",
                    "At most " + MAX_TWITCHAT_PAYLOADS + " payloads per event"));
        }
        List<TwitchatValidationError> tooBig = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            String dataJson = payloads.get(i) == null ? null : payloads.get(i).dataJson();
            if (dataJson != null && dataJson.length() > MAX_TWITCHAT_DATA_JSON) {
                tooBig.add(new TwitchatValidationError(i, "dataJson", "At most " + MAX_TWITCHAT_DATA_JSON + " characters"));
            }
        }
        return tooBig.isEmpty() ? twitchatValidator.validate(payloads) : tooBig;
    }

    @Override
    public List<TwitchatValidationError> saveTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        log.trace("saveTwitchatEvent({}, {})", username, eventType);
        List<TwitchatValidationError> errors = validateTwitchat(payloads);
        if (errors.isEmpty()) currentData().setTwitchatEvent(eventType, payloads);
        return errors;
    }

    @Override
    public List<TwitchatValidationError> testTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        log.trace("testTwitchatEvent({}, {})", username, eventType);
        List<TwitchatValidationError> errors = validateTwitchat(payloads);
        if (errors.isEmpty()) {
            // As the API's test: every button shows, its token leading nowhere.
            publishTwitchat(username, eventType, render(payloads, Map.of("gameName", "Jeu de test", "previousGameName", "Jeu précédent"),
                    EnumSet.allOf(TwitchatActionType.class)));
        }
        return errors;
    }

    /**
     * Sends the session's enabled payloads of {@code eventType} to its Twitchat, as catapult-api's
     * TwitchatEventDispatcher would when Catapult sees the event: variables filled, buttons of
     * the event's Catapult actions kept (with mock tokens). Returns how many payloads went.
     */
    public int dispatchTwitchatEvent(String eventType, Map<String, String> eventVariables) {
        log.trace("dispatchTwitchatEvent({}, {})", eventType, eventVariables);
        MockData current = currentData();
        if (current == null) return 0;
        List<TwitchatEventActionDto> enabled = current.getTwitchatEvents().getOrDefault(eventType, List.of()).stream()
                .filter(TwitchatEventActionDto::enabled)
                .toList();
        Set<TwitchatActionType> applicable = EnumSet.noneOf(TwitchatActionType.class);
        applicable.addAll(TwitchatEvents.ACTIONS.getOrDefault(eventType, List.of()));
        List<TwitchatPayload> rendered = render(enabled, eventVariables, applicable);
        publishTwitchat(current.getChannelDto().twitchUsername(), eventType, rendered);
        return rendered.size();
    }

    private List<TwitchatPayload> render(List<TwitchatEventActionDto> payloads, Map<String, String> eventVariables,
                                         Set<TwitchatActionType> applicable) {
        Map<String, String> variables = new HashMap<>(eventVariables);
        variables.put("baseUrl", ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
        MockData current = currentData();
        variables.put("channel", current == null ? "" : current.getChannelDto().twitchUsername());
        TwitchatPayloadRenderer renderer = new TwitchatPayloadRenderer(variables, applicable,
                action -> "mock-" + action.name().toLowerCase() + "-" + UUID.randomUUID());
        List<TwitchatPayload> rendered = new ArrayList<>();
        for (TwitchatEventActionDto payload : payloads) {
            renderer.render(payload.action(), twitchatValidator.parse(payload.dataJson())).ifPresent(rendered::add);
        }
        return rendered;
    }

    private void publishTwitchat(String username, String eventType, List<TwitchatPayload> payloads) {
        if (!payloads.isEmpty()) {
            twitchatHub.publish(currentUserId(), JSON.writeValueAsString(new TwitchatDispatch(username, eventType, payloads)));
        }
    }

    @Override
    public boolean executeTwitchatAction(String token) {
        log.trace("executeTwitchatAction({})", token);
        return true;
    }

    @Override
    public UserInfoResponse accountInfo() {
        log.trace("accountInfo()");
        MockData current = currentData();
        if (current == null) return null;
        var channel = current.getChannelDto();
        return new UserInfoResponse(channel.id(), channel.twitchId(), channel.twitchUsername(),
                channel.profileImageUrl(), current.getAccountStatus(),
                current.isAdmin() ? List.of("ROLE_USER", "ROLE_ADMIN") : List.of("ROLE_USER"),
                current.getDeletionRequestedAt() == null ? null : current.getDeletionRequestedAt().toString());
    }

    @Override
    public int validateSession() {
        // the mock's tokens never expire
        return 200;
    }

    @Override
    public int accountDeletionDelayDays() {
        return 7;
    }

    @Override
    public int deleteAccount(String username, String confirmUsername) {
        log.trace("deleteAccount({})", username);
        MockData current = currentData();
        if (current == null) return 401;
        if (!current.getChannelDto().twitchUsername().equals(username)) return 403;
        current.requestAccountDeletion(confirmUsername);
        return 204;
    }

    @Override
    public int cancelAccountDeletion(String username) {
        log.trace("cancelAccountDeletion({})", username);
        MockData current = currentData();
        if (current == null) return 401;
        if (!current.getChannelDto().twitchUsername().equals(username)) return 403;
        current.cancelAccountDeletion();
        return 204;
    }

    @Override
    public InvitePageData invitePage() {
        log.trace("invitePage()");
        MockData current = currentData();
        return current == null ? null : current.invitePage();
    }

    @Override
    public int regenerateInvite() {
        log.trace("regenerateInvite()");
        MockData current = currentData();
        if (current == null) return 401;
        current.regenerateInvite();
        return 204;
    }

    // The key itself is never logged, only the provider.
    @Override
    public ApiKeysPageDto adminApiKeys(ApiKeyProvider provider) {
        log.trace("adminApiKeys({})", provider);
        return apiKeys.page(provider);
    }

    @Override
    public int adminAddApiKey(ApiKeyProvider provider, String apiKey) {
        log.trace("adminAddApiKey({})", provider);
        return apiKeys.add(provider, apiKey);
    }

    @Override
    public int adminDeleteApiKey(ApiKeyProvider provider, String keyId) {
        log.trace("adminDeleteApiKey({})", provider);
        return apiKeys.delete(provider, keyId);
    }

    @Override
    public int adminRefreshApiKeys(ApiKeyProvider provider) {
        log.trace("adminRefreshApiKeys({})", provider);
        return apiKeys.refresh();
    }

    @Override
    public List<AccountDto> adminMinecraftAccounts() {
        log.trace("adminMinecraftAccounts()");
        return minecraftAccounts.list();
    }

    @Override
    public int adminPatchMinecraftAccount(UUID id, Map<String, Object> patch) {
        log.trace("adminPatchMinecraftAccount({}, {})", id, patch);
        return minecraftAccounts.patch(id, patch);
    }

    @Override
    public int adminDeleteMinecraftAccount(UUID id) {
        log.trace("adminDeleteMinecraftAccount({})", id);
        return minecraftAccounts.delete(id);
    }

    @Override
    public MinecraftDeviceCodeStart adminMinecraftDeviceCode() {
        log.trace("adminMinecraftDeviceCode()");
        return new MinecraftDeviceCodeStart(200, minecraftAccounts.deviceCode(), null);
    }

    @Override
    public ApiClient.Result<Map<String, Object>> adminCreateMinecraftAccount(String deviceCode, String label) {
        log.trace("adminCreateMinecraftAccount({})", label);
        return minecraftAccounts.create(deviceCode, label);
    }

    @Override
    public ApiClient.Result<Map<String, Object>> adminReauthMinecraftAccount(UUID id, String deviceCode) {
        log.trace("adminReauthMinecraftAccount({})", id);
        return minecraftAccounts.reauth(id, deviceCode);
    }

    @Override
    public List<TwDefinitionDto> adminTws() {
        log.trace("adminTws()");
        return tws.list();
    }

    @Override
    public TwDefinitionDto adminTw(String id) {
        log.trace("adminTw({})", id);
        return tws.find(id).orElse(null);
    }

    @Override
    public int adminCreateTw(String id, String label, int sortOrder) {
        log.trace("adminCreateTw({}, {}, {})", id, label, sortOrder);
        return tws.create(id, label, sortOrder);
    }

    @Override
    public int adminRebuildTws(TwRebuild mode) {
        log.trace("adminRebuildTws({})", mode);
        return tws.rebuild();
    }

    @Override
    public List<String> adminTwKeywords(String id) {
        log.trace("adminTwKeywords({})", id);
        return tws.keywords(id);
    }

    @Override
    public int adminAddTwKeyword(String id, String keyword) {
        log.trace("adminAddTwKeyword({}, {})", id, keyword);
        return tws.addKeyword(id, keyword);
    }

    @Override
    public int adminRemoveTwKeyword(String id, String keyword) {
        log.trace("adminRemoveTwKeyword({}, {})", id, keyword);
        return tws.removeKeyword(id, keyword);
    }

    @Override
    public TwKeywordTestDto adminTestTwKeyword(String id, String appId, String draftKeyword) {
        log.trace("adminTestTwKeyword({}, {}, {})", id, appId, draftKeyword);
        return tws.test(id, appId, draftKeyword);
    }

    @Override
    public fr.enimaloc.catapult.common.dto.HelpContent help(String cardId, java.util.Locale locale) {
        log.trace("help({}, {})", cardId, locale);
        boolean french = "fr".equals(locale.getLanguage());
        return new fr.enimaloc.catapult.common.dto.HelpContent(
                (french ? "Aide : " : "Help: ") + cardId,
                french ? "<p>Texte d'aide de démonstration pour <strong>" + cardId + "</strong>.</p>"
                        : "<p>Sample help text for <strong>" + cardId + "</strong>.</p>");
    }

    /** Like Steam's login sending the popup back: the account is linked at once, then the popup's closing page. */
    @Override
    public String steamConnectUrl() {
        log.trace("steamConnectUrl()");
        MockData current = currentData();
        if (current == null || !current.isHasSteamProvider()) return null;
        current.steamConnect();
        publishSteamConnection(current);
        return "/connect/steam/close";
    }

    @Override
    public int steamDisconnect() {
        log.trace("steamDisconnect()");
        MockData current = currentData();
        if (current == null) return 401;
        current.steamDisconnect();
        publishSteamConnection(current);
        return NO_CONTENT;
    }

    /** The Steam card's events for {@code current}'s link, as ChannelEventRedisBridge relays catapult-api's. */
    private void publishSteamConnection(MockData current) {
        String username = current.getChannelDto().twitchUsername();
        boolean connected = current.isHasSteam();
        eventPublisher.publishEvent(new ChannelConnectionChangedEvent(username, "STEAM", connected));
        eventPublisher.publishEvent(new SteamConnectionStateEvent(username, connected,
                connected && current.isSteamRateLimited(), connected && current.isSteamOfflineMode(),
                connected && current.isSteamProfilePrivate()));
    }

    @Override
    public List<ChangelogSection> changelog() {
        log.trace("changelog()");
        return MOCK_CHANGELOG;
    }

    @Override
    public DegradedStatusDto degradedStatus() {
        log.trace("degradedStatus()");
        return degraded ? new DegradedStatusDto(true, 60, 15) : new DegradedStatusDto(false, null, null);
    }

    /** The mock's catapult-api is always up. */
    @Override
    public boolean apiAvailable() {
        log.trace("apiAvailable()");
        return true;
    }

    private static final List<ChangelogSection> MOCK_CHANGELOG = List.of(
            new ChangelogSection("0.4.0", List.of(
                    new ChangelogSection.Entry("c0ffee1", "feat", "web", "link a Steam account from its card", false),
                    new ChangelogSection.Entry("c0ffee2", "feat", "api", "name the channel by login in its events", true),
                    new ChangelogSection.Entry("c0ffee3", "fix", "web", "keep a navbar's live states per stream", false),
                    new ChangelogSection.Entry("c0ffee4", "chore", "release", "0.4.0", false))),
            new ChangelogSection("0.3.9", List.of(
                    new ChangelogSection.Entry("bead001", "fix", null, "open an OBS widget on its owner's Twitchat branch", false),
                    new ChangelogSection.Entry("bead002", "perf", "web", "build each expanded message's format once", false))));
}
