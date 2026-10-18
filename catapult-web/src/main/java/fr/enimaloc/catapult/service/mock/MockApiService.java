package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
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
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
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
import fr.enimaloc.catapult.event.minecraft.MinecraftDisconnectedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftEnrollEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamTokenDeletedEvent;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@Profile("mock")
public class MockApiService implements ApiService {
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    /** Application-wide, like the well-known files: a notification sent in one session reaches the others. */
    private MockNotificationStore notifications;

    private synchronized MockNotificationStore notifications() {
        if (notifications == null) notifications = new MockNotificationStore(eventPublisher);
        return notifications;
    }

    /**
     * Resolves the calling session's data the same way {@link ApiClient} resolves its JWT.
     * The token is a random UUID minted in {@link #exchangeCode}, not a JWT, so it can't be
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
        String token = UUID.randomUUID().toString();
        data.put(token, mockData);
        return new TokenResponse(token);
    }

    @Override
    public ChannelListResponse channelList() {
        log.trace("channelList()");
        return currentData().getChannelList();
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        log.trace("channelPage({}, {}, {}, {})", username, page, status, source);
        return currentData().getPage(username, status, source);
    }

    @Override
    public void toggleBot(String username) {
        log.trace("toggleBot({})", username);
        MockData data = currentData();
        data.setBotEnabled(!data.isBotEnabled());
        eventPublisher.publishEvent(new BotStateChangedEvent(username, data.isBotEnabled()));
        dispatchTwitchatEvent(data.isBotEnabled() ? "BOT_ENABLED" : "BOT_DISABLED", Map.of());
    }

    @Override
    public void recheckGame(String username) {
        log.trace("recheckGame({})", username);
        // no-op in mock mode
    }

    @Override
    public GameDto currentGame(String username) {
        log.trace("currentGame({})", username);
        return currentData().getGameDto();
    }

    @Override
    public void cclToggle(String username, String bindingId, boolean enabled) {
        log.trace("cclToggle({}, {}, {})", username, bindingId, enabled);
        currentData().setBindingCclEnabled(bindingId, enabled);
        eventPublisher.publishEvent(new CclStateEvent(username, bindingId, enabled));
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        log.trace("ignoredToggle({}, {}, {})", username, bindingId, ignored);
        currentData().setBindingIgnored(bindingId, ignored);
        eventPublisher.publishEvent(new BindingIgnoredStateEvent(username, bindingId, ignored));
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        log.trace("deleteBinding({}, {})", username, bindingId);
        currentData().deleteBinding(bindingId);
        eventPublisher.publishEvent(new BindingDeletedEvent(username, bindingId));
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
    public void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, Set<String> ccls) {
        log.trace("updateBinding({}, {}, {}, {}, {})", username, bindingId, twitchGameId, twitchGameName, ccls);
        currentData().updateBindingGame(bindingId, twitchGameId, twitchGameName, ccls);
        eventPublisher.publishEvent(new BindingUpdatedEvent(username, bindingId, twitchGameId, twitchGameName, ccls));
    }

    @Override
    public void saveTws(String bindingId, Set<String> tws) {
        log.trace("saveTws({}, {})", bindingId, tws);
        MockData data = currentData();
        data.setBindingTws(bindingId, tws);
        eventPublisher.publishEvent(new TwUpdatedEvent(data.getChannelDto().twitchUsername(), bindingId, tws));
    }

    @Override
    public void resetTws(String bindingId) {
        log.trace("resetTws({})", bindingId);
        MockData data = currentData();
        data.resetBindingTws(bindingId);
        eventPublisher.publishEvent(new TwResetEvent(data.getChannelDto().twitchUsername(), bindingId));
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        log.trace("toggleTwEnabled({}, {})", bindingId, enabled);
        MockData data = currentData();
        data.setBindingTwEnabled(bindingId, enabled);
        eventPublisher.publishEvent(new TwEnabledStateEvent(data.getChannelDto().twitchUsername(), bindingId, enabled));
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        log.trace("saveSteamToken({}, {}, {})", username, token, shared);
        currentData().saveSteamToken(shared);
        eventPublisher.publishEvent(new SteamTokenSavedEvent(username, shared));
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        log.trace("steamTokenSharing({}, {})", username, shared);
        currentData().setSteamTokenShared(shared);
        eventPublisher.publishEvent(new SteamTokenSharedStateEvent(username, shared));
    }

    @Override
    public void deleteSteamToken(String username) {
        log.trace("deleteSteamToken({})", username);
        currentData().deleteSteamToken();
        eventPublisher.publishEvent(new SteamTokenDeletedEvent(username));
    }

    @Override
    public void refreshSteamProfileCache(String username) {
        log.trace("refreshSteamProfileCache({})", username);
        // no-op in mock mode
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        log.trace("minecraftStatus({})", username);
        return currentData().getMinecraftLink();
    }

    @Override
    public void minecraftEnroll(String username, String name) {
        log.trace("minecraftEnroll({}, {})", username, name);
        MockData data = currentData();
        data.minecraftEnroll(name);
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftEnrollEvent(username, link.status(), link.minecraftName()));
    }

    @Override
    public void minecraftSync(String username) {
        log.trace("minecraftSync({})", username);
        MockData data = currentData();
        data.minecraftSync();
        LinkStateResponse link = data.getMinecraftLink();
        eventPublisher.publishEvent(new MinecraftSyncEvent(username, link.status(), link.minecraftName()));
    }

    @Override
    public void minecraftDisconnect(String username) {
        log.trace("minecraftDisconnect({})", username);
        currentData().minecraftDisconnect();
        eventPublisher.publishEvent(new MinecraftDisconnectedEvent(username));
    }

    @Override
    public TwitchatWidgetConfig obsConnection() {
        log.trace("obsConnection()");
        MockData current = currentData();
        return current == null ? null : current.obsConnection();
    }

    @Override
    public void saveObsSettings(String username, boolean enabled, String host, Integer port, String password) {
        log.trace("saveObsSettings({}, {}, {}, {})", username, enabled, host, port);
        currentData().saveObsSettings(enabled, host, port, password);
    }

    @Override
    public void saveTwitchatBranch(String username, String branch) {
        log.trace("saveTwitchatBranch({}, {})", username, branch);
        currentData().saveTwitchatBranch(branch);
    }

    @Override
    public void saveCclSettings(String username, boolean enabled, Set<String> blockedCcls) {
        log.trace("saveCclSettings({}, {}, {})", username, enabled, blockedCcls);
        currentData().saveCclSettings(enabled, blockedCcls);
    }

    @Override
    public void saveTwSettings(String username, boolean enabled, Set<String> blockedTws) {
        log.trace("saveTwSettings({}, {}, {})", username, enabled, blockedTws);
        currentData().saveTwSettings(enabled, blockedTws);
    }

    @Override
    public UserSettingsDto channelSettings(String username) {
        log.trace("channelSettings({})", username);
        return currentData().getUserSettings();
    }

    @Override
    public void saveNoGameSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        log.trace("saveNoGameSettings({}, {}, {}, {}, {}, {}, {})", username, twitchGameId, twitchGameName,
                ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
        currentData().saveNoGameSettings(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd);
    }

    @Override
    public void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls) {
        log.trace("saveIncompleteFallbackSettings({}, {}, {}, {})", username, twitchGameId, twitchGameName, ccls);
        currentData().saveIncompleteFallbackSettings(twitchGameId, twitchGameName, ccls);
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
    public void dtddValidate(String igdbId) {
        log.trace("dtddValidate({})", igdbId);
        // no-op in mock mode
    }

    @Override
    public void dtddPropose(String igdbId, Long dtddId, String reason) {
        log.trace("dtddPropose({}, {}, {})", igdbId, dtddId, reason);
        // no-op in mock mode
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
    public AdminMembersPage adminMembers() {
        log.trace("adminMembers()");
        return currentData().getMembers().page();
    }

    @Override
    public void adminToggleMemberBot(UUID memberId) {
        log.trace("adminToggleMemberBot({})", memberId);
        currentData().getMembers().toggleBot(memberId);
    }

    @Override
    public void adminUnlinkMemberSteam(UUID memberId) {
        log.trace("adminUnlinkMemberSteam({})", memberId);
        currentData().getMembers().unlinkSteam(memberId);
    }

    @Override
    public void adminUnlinkMemberTwitch(UUID memberId) {
        log.trace("adminUnlinkMemberTwitch({})", memberId);
        currentData().getMembers().unlinkTwitch(memberId);
    }

    @Override
    public void adminPromoteMemberToSystem(UUID memberId) {
        log.trace("adminPromoteMemberToSystem({})", memberId);
        currentData().getMembers().promoteToSystem(memberId);
    }

    @Override
    public void adminDeleteMember(UUID memberId) {
        log.trace("adminDeleteMember({})", memberId);
        currentData().getMembers().delete(memberId);
    }

    @Override
    public void adminMigrateMember(UUID memberId, UUID targetId, boolean settings, boolean getters, boolean bindings) {
        log.trace("adminMigrateMember({}, {}, {}, {}, {})", memberId, targetId, settings, getters, bindings);
        // no-op in mock mode: a mock session holds a single channel's data
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
    public AdminPage<AdminNotificationDto> adminNotifications(int page) {
        log.trace("adminNotifications({})", page);
        return notifications().adminPage(page);
    }

    @Override
    public int adminCreateNotification(AdminNotificationCreateBody body) {
        log.trace("adminCreateNotification({})", body);
        UUID creator = currentUserId();
        // The creator's bell exists before an "everyone" notification goes out, so it reaches them too.
        if (creator != null) notifications().snapshot(creator);
        return notifications().create(body.title(), body.body(), body.severity(), body.ctaUrl(), body.ctaLabel(),
                body.expiresAt(), body.targetUserId());
    }

    @Override
    public int adminDeleteNotification(UUID id) {
        log.trace("adminDeleteNotification({})", id);
        return notifications().delete(id);
    }

    @Override
    public String adminPreviewNotification(String body) {
        log.trace("adminPreviewNotification()");
        return body == null || body.isBlank() ? null : notifications().preview(body);
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

    @Override
    public List<TwitchatValidationError> saveTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        log.trace("saveTwitchatEvent({}, {})", username, eventType);
        List<TwitchatValidationError> errors = twitchatValidator.validate(payloads);
        if (errors.isEmpty()) currentData().setTwitchatEvent(eventType, payloads);
        return errors;
    }

    @Override
    public List<TwitchatValidationError> testTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        log.trace("testTwitchatEvent({}, {})", username, eventType);
        List<TwitchatValidationError> errors = twitchatValidator.validate(payloads);
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
}
