package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.common.dto.UserInfoResponse;
import fr.enimaloc.catapult.common.dto.AppConfigResponse;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.DeleteAccountRequest;
import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import fr.enimaloc.catapult.common.dto.admin.ImpersonateRequest;
import fr.enimaloc.catapult.common.dto.admin.ImpersonateResponse;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.FlagKeyDto;
import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentOverrideRequest;
import fr.enimaloc.catapult.common.dto.experiment.MoveAssignmentRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRebalanceDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRuleRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto;
import fr.enimaloc.catapult.common.dto.admin.AdminTwCreateBody;
import fr.enimaloc.catapult.common.dto.admin.SteamKeywordBody;
import fr.enimaloc.catapult.common.dto.admin.SteamSignalTestBody;
import fr.enimaloc.catapult.common.dto.admin.TwDefinitionDto;
import fr.enimaloc.catapult.common.dto.admin.TwKeywordTestDto;
import fr.enimaloc.catapult.common.dto.admin.TwRebuild;
import fr.enimaloc.catapult.common.dto.admin.AccountDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagDto;
import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.common.dto.admin.MinecraftDeviceCodeDto;
import fr.enimaloc.catapult.common.dto.admin.DtddKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.SteamKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewRequest;
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
import fr.enimaloc.catapult.common.dto.admin.WellKnownResponseDto;
import fr.enimaloc.catapult.service.WellKnownContent;
import fr.enimaloc.catapult.common.dto.admin.SaveMappingsRequest;
import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.common.dto.channel.CclSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.ObsSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.TwitchatBranchRequest;
import fr.enimaloc.catapult.common.dto.channel.ProposeRequest;
import fr.enimaloc.catapult.common.dto.channel.SaveBody;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenRequest;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenSharingRequest;
import fr.enimaloc.catapult.common.dto.channel.TwEnabledBody;
import fr.enimaloc.catapult.common.dto.channel.TwSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.UpdateBindingRequest;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.channel.ValidateRequest;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatSettingsResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetAccessResponse;
import fr.enimaloc.catapult.common.dto.channel.ConfigResponse;
import fr.enimaloc.catapult.common.dto.channel.TwitchatData;
import fr.enimaloc.catapult.common.dto.connect.SteamStartResponse;
import fr.enimaloc.catapult.dto.changelog.ChangelogSection;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Profile("!mock")
public class RealApiService implements ApiService {
    /** catapult-api's own default, used when its config can't be read. */
    private static final int DEFAULT_DELETION_DELAY_DAYS = 7;
    private static final ParameterizedTypeReference<Map<String, List<TwitchatEventActionDto>>> EVENTS = new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<AdminPage<CacheEntryDto>> CACHE_ENTRIES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<WellKnownFileDto>> WELL_KNOWN_FILES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<String>> FEATURE_KEYS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AdminPage<Map<String, Object>>> DATA_ROWS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> DATA_ROW = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AdminPage<NotificationDto>> NOTIFICATIONS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<AdminMemberDto>> MEMBERS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<DtddProposalDto>> DTDD_PROPOSALS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<TwDefinitionDto>> TWS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto>> ENABLED_COMMANDS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<GroupSummaryDto>> GROUPS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ExperimentSummaryDto>> EXPERIMENTS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<FlagKeyDto>> FLAG_KEYS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AdminPage<AdminNotificationDto>> ADMIN_NOTIFICATIONS = new ParameterizedTypeReference<>() {};

    private final ApiClient client;

    @Override
    public TokenResponse exchangeCode(String code) {
        return client.post("/api/auth/exchange?code={code}", TokenResponse.class, code);
    }

    @Override
    public ChannelListResponse channelList() {
        return client.get("/api/channels", ChannelListResponse.class);
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        StringBuilder path = new StringBuilder("/api/channels/{username}?page={page}");
        List<Object> vars = new ArrayList<>(List.of(username, page));
        if (status != null && !status.isBlank()) {
            path.append("&status={status}");
            vars.add(status);
        } else if (source != null && !source.isBlank()) {
            path.append("&source={source}");
            vars.add(source);
        }
        return client.get(path.toString(), ChannelPageData.class, vars.toArray());
    }

    @Override
    public int toggleBot(String username) {
        return client.postVoid("/api/channels/{username}/settings/bot", null, username);
    }

    @Override
    public int recheckGame(String username) {
        return client.postVoid("/api/channels/{username}/game/recheck", null, username);
    }

    @Override
    public int cclToggle(String username, String bindingId, boolean enabled) {
        return client.postVoid("/api/channels/{username}/bindings/{bindingId}/ccl-toggle",
                new CclToggleRequest(enabled), username, bindingId);
    }

    @Override
    public int ignoredToggle(String username, String bindingId, boolean ignored) {
        return client.postVoid("/api/channels/{username}/bindings/{bindingId}/ignored-toggle",
                new IgnoredToggleRequest(ignored), username, bindingId);
    }

    @Override
    public int deleteBinding(String username, String bindingId) {
        return client.postVoid("/api/channels/{username}/bindings/{bindingId}/delete", null, username, bindingId);
    }

    @Override
    public Object searchGames(String username, String q) {
        return client.get("/api/channels/{username}/games/search?q={q}", Object.class, username, q);
    }

    @Override
    public int updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, Set<String> ccls) {
        return client.postVoid("/api/channels/{username}/bindings/{bindingId}",
                new UpdateBindingRequest(twitchGameId, twitchGameName, ccls), username, bindingId);
    }

    @Override
    public int saveTws(String bindingId, Set<String> tws) {
        return client.postVoid("/api/channel/bindings/{bindingId}/tws", new SaveBody(tws), bindingId);
    }

    @Override
    public int resetTws(String bindingId) {
        return client.postVoid("/api/channel/bindings/{bindingId}/tws/reset", null, bindingId);
    }

    @Override
    public int toggleTwEnabled(String bindingId, boolean enabled) {
        return client.postVoid("/api/channel/bindings/{bindingId}/tw-enabled", new TwEnabledBody(enabled), bindingId);
    }

    @Override
    public int saveSteamToken(String username, String token, boolean shared) {
        return client.postVoid("/api/channels/{username}/settings/steam-personal-token",
                new SteamTokenRequest(token, shared), username);
    }

    @Override
    public int steamTokenSharing(String username, boolean shared) {
        return client.postVoid("/api/channels/{username}/settings/steam-personal-token/sharing",
                new SteamTokenSharingRequest(shared), username);
    }

    @Override
    public int deleteSteamToken(String username) {
        return client.postVoid("/api/channels/{username}/settings/steam-personal-token/delete", null, username);
    }

    @Override
    public int refreshSteamProfileCache(String username) {
        return client.postVoid("/api/channels/{username}/steam/refresh-profile-cache", null, username);
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        return client.get("/api/connect/minecraft", LinkStateResponse.class);
    }

    @Override
    public int minecraftEnroll(String username, String name) {
        return client.postVoid("/api/connect/minecraft", Map.of("name", name));
    }

    @Override
    public int minecraftSync(String username) {
        return client.postVoid("/api/connect/minecraft/sync", null);
    }

    @Override
    public int minecraftDisconnect(String username) {
        return client.delete("/api/connect/minecraft");
    }

    @Override
    public int xboxDisconnect(String username) {
        return client.postVoid("/api/channels/{username}/settings/disconnect", Map.of("provider", "XBOX"), username);
    }

    @Override
    public TwitchatWidgetConfig obsConnection() {
        return client.get("/api/me/obs", TwitchatWidgetConfig.class);
    }

    @Override
    public TwitchatWidgetConfig widgetObsConfig(UUID token) {
        // The body carries the OBS password: never logged.
        ApiClient.Result<ConfigResponse> result = client.getAnonymous("/api/twitchat/widget/{token}", ConfigResponse.class, token);
        ConfigResponse config = result.status() == 200 ? result.body() : null;
        // The owner's Twitchat branch; "auto" (detected by the widget) when catapult-api tells none.
        return config == null ? null : new TwitchatWidgetConfig(config.obsHost(), config.obsPort(), config.obsPassword(),
                config.twitchatBranch() == null ? TwitchatData.AUTO : config.twitchatBranch());
    }

    @Override
    public ApiClient.Result<TwitchatWidgetAccessResponse> widgetAccess(UUID token) {
        return client.getAnonymous("/api/twitchat/widget/{token}/access", TwitchatWidgetAccessResponse.class, token);
    }

    @Override
    public TwitchatSettingsResponse twitchatSettings(String username) {
        return client.get("/api/channels/{username}/settings/twitchat", TwitchatSettingsResponse.class, username);
    }

    @Override
    public ApiClient.Result<TwitchatSettingsResponse> regenerateTwitchatWidget(String username) {
        // The answer carries the new token: never logged.
        ApiClient.Result<TwitchatSettingsResponse> result = client.call(HttpMethod.POST,
                "/api/channels/{username}/settings/twitchat/regenerate", null, TwitchatSettingsResponse.class, username);
        return result.ok() ? result : new ApiClient.Result<>(result.status(), null);
    }

    @Override
    public int saveObsSettings(String username, boolean enabled, String host, Integer port, String password) {
        return client.postVoid("/api/channels/{username}/settings/obs",
                new ObsSettingsRequest(enabled, host, port, password), username);
    }

    @Override
    public int saveTwitchatBranch(String username, String branch) {
        return client.postVoid("/api/channels/{username}/settings/twitchat/branch", new TwitchatBranchRequest(branch), username);
    }

    @Override
    public int saveCclSettings(String username, boolean enabled, Set<String> blockedCcls) {
        return client.postVoid("/api/channels/{username}/settings/ccl",
                new CclSettingsRequest(enabled, blockedCcls), username);
    }

    @Override
    public int saveTwSettings(String username, boolean enabled, Set<String> blockedTws) {
        return client.postVoid("/api/channels/{username}/settings/tws",
                new TwSettingsRequest(enabled, blockedTws), username);
    }

    @Override
    public UserSettingsDto channelSettings(String username) {
        return client.get("/api/channels/{username}/settings", UserSettingsDto.class, username);
    }

    @Override
    public BindingDto currentBinding(String username) {
        return client.get("/api/channels/{username}/bindings/current", BindingDto.class, username);
    }

    @Override
    public List<fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto> enabledChatCommands() {
        var commands = client.get("/api/chat-commands/enabled", ENABLED_COMMANDS);
        return commands == null ? List.of() : commands;
    }

    @Override
    public int saveNoGameSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        return client.postVoid("/api/channels/{username}/settings/no-game",
                new NoGameSettingsRequest(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd),
                username);
    }

    @Override
    public int saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls) {
        return client.postVoid("/api/channels/{username}/settings/incomplete-fallback",
                new IncompleteFallbackRequest(twitchGameId, twitchGameName, ccls), username);
    }

    @Override
    public DtddMappingStatusDto dtddMappingStatus(String username) {
        return client.get("/api/channels/{username}/dtdd-mapping", DtddMappingStatusDto.class, username);
    }

    @Override
    public SearchResponse dtddSearch(String q) {
        return client.get("/api/channel/dtdd-mapping/search?q={q}", SearchResponse.class, q);
    }

    @Override
    public int dtddValidate(String igdbId) {
        return client.postVoid("/api/channel/dtdd-mapping/validate", new ValidateRequest(igdbId));
    }

    @Override
    public int dtddPropose(String igdbId, Long dtddId, String reason) {
        return client.postVoid("/api/channel/dtdd-mapping/propose", new ProposeRequest(igdbId, dtddId, reason));
    }

    @Override
    public UUID currentUserId() {
        return ApiClient.currentSubject();
    }

    @Override
    public boolean isAdmin() {
        return ApiClient.currentRoles().contains("ROLE_ADMIN");
    }

    @Override
    public Map<String, List<TwitchatEventActionDto>> twitchatEvents(String username) {
        Map<String, List<TwitchatEventActionDto>> events = client.get("/api/channels/{username}/twitchat/events", EVENTS, username);
        return events == null ? Map.of() : events;
    }

    @Override
    public List<TwitchatValidationError> saveTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        return errors(client.call(HttpMethod.PUT, "/api/channels/{username}/twitchat/events/{eventType}/actions",
                payloads, TwitchatValidationError[].class, username, eventType));
    }

    @Override
    public List<TwitchatValidationError> testTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        return errors(client.call(HttpMethod.POST, "/api/channels/{username}/twitchat/events/{eventType}/test",
                payloads, TwitchatValidationError[].class, username, eventType));
    }

    @Override
    public boolean executeTwitchatAction(String token) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = client.post("/api/twitchat/actions/{token}", Map.class, token);
        return result != null && "EXECUTED".equals(result.get("result"));
    }

    @Override
    public AdminMembersPage adminMembers(String q, int page) {
        return client.get("/api/admin/members?page={page}&q={q}", AdminMembersPage.class, page, q == null ? "" : q);
    }

    @Override
    public List<AdminMemberDto> adminSearchMembers(String q) {
        return client.get("/api/admin/members/search?q={q}", MEMBERS, q == null ? "" : q);
    }

    @Override
    public int adminToggleMemberBot(UUID memberId) {
        return client.postVoid("/api/admin/members/{id}/bot/toggle", null, memberId);
    }

    @Override
    public int adminUnlinkMemberSteam(UUID memberId) {
        return client.postVoid("/api/admin/members/{id}/steam/unlink", null, memberId);
    }

    @Override
    public int adminUnlinkMemberTwitch(UUID memberId) {
        return client.postVoid("/api/admin/members/{id}/twitch/unlink", null, memberId);
    }

    @Override
    public int adminPromoteMemberToSystem(UUID memberId) {
        return client.postVoid("/api/admin/members/{id}/promote-to-system", null, memberId);
    }

    @Override
    public int adminDeleteMember(UUID memberId) {
        return client.postVoid("/api/admin/members/{id}/delete", null, memberId);
    }

    @Override
    public int adminMigrateMember(UUID memberId, UUID targetId, boolean settings, boolean getters, boolean bindings) {
        return client.postVoid("/api/admin/members/{id}/migrate", new MigrateRequest(targetId, settings, getters, bindings), memberId);
    }

    @Override
    public AdminCclPage adminCcl() {
        return client.get("/api/admin/ccl", AdminCclPage.class);
    }

    @Override
    public int adminRefreshCcl() {
        return client.call(HttpMethod.POST, "/api/admin/ccl/refresh", null, Void.class).status();
    }

    @Override
    public int adminSaveCclMappings(String cclId, Set<Long> igdbDescriptorIds) {
        return client.call(HttpMethod.POST, "/api/admin/ccl/{id}/mappings",
                new SaveMappingsRequest(igdbDescriptorIds), Void.class, cclId).status();
    }

    @Override
    public List<CacheSummaryDto> adminCaches() {
        CacheSummaryDto[] caches = client.get("/api/admin/caches", CacheSummaryDto[].class);
        return caches == null ? null : List.of(caches);
    }

    @Override
    public AdminPage<CacheEntryDto> adminCacheEntries(String name, String q, int page) {
        return client.get("/api/admin/caches/{name}?q={q}&page={page}", CACHE_ENTRIES, name, q == null ? "" : q, page);
    }

    @Override
    public CacheEntryDetailDto adminCacheEntry(String name, String key) {
        return client.get("/api/admin/caches/{name}/entry?key={key}", CacheEntryDetailDto.class, name, key);
    }

    @Override
    public int adminDeleteCacheEntry(String name, String key) {
        return client.call(HttpMethod.DELETE, "/api/admin/caches/{name}?key={key}", null, Void.class, name, key).status();
    }

    @Override
    public List<RepoSummaryDto> adminDataRepos() {
        RepoSummaryDto[] repos = client.get("/api/admin/data", RepoSummaryDto[].class);
        return repos == null ? null : List.of(repos);
    }

    @Override
    public AdminPage<Map<String, Object>> adminDataRows(String repo, String q, int page) {
        return client.get("/api/admin/data/{repo}?q={q}&page={page}", DATA_ROWS, repo, q == null ? "" : q, page);
    }

    @Override
    public Map<String, Object> adminDataRow(String repo, String id) {
        return client.get("/api/admin/data/{repo}/{id}", DATA_ROW, repo, id);
    }

    @Override
    public int adminCreateDataRow(String repo, Map<String, String> fields) {
        return client.call(HttpMethod.POST, "/api/admin/data/{repo}", fields, Void.class, repo).status();
    }

    @Override
    public int adminUpdateDataRow(String repo, String id, Map<String, String> fields) {
        return client.call(HttpMethod.PUT, "/api/admin/data/{repo}/{id}", fields, Void.class, repo, id).status();
    }

    @Override
    public WellKnownContent wellKnown(WellKnownLocation location, String path) {
        ApiClient.Result<WellKnownResponseDto> result = client.getAnonymous(
                "/api/well-known/serve?location={location}&path={path}", WellKnownResponseDto.class, location.name(), path);
        if (result.status() == 200 && result.body() == null) return new WellKnownContent(502, null);
        return new WellKnownContent(result.status(), result.body());
    }

    @Override
    public String adminWellKnownSchema(String path) {
        return client.get("/api/admin/well-known/schema?path={path}", String.class, path);
    }

    @Override
    public List<WellKnownFileDto> adminWellKnownFiles() {
        return client.get("/api/admin/well-known", WELL_KNOWN_FILES);
    }

    @Override
    public WellKnownFileDetailDto adminWellKnownFile(long id) {
        return client.get("/api/admin/well-known/{id}", WellKnownFileDetailDto.class, id);
    }

    @Override
    public IanaRegistryDto adminIanaRegistry() {
        return client.get("/api/admin/well-known/iana", IanaRegistryDto.class);
    }

    @Override
    public int adminCreateWellKnownFile(WellKnownFileRequest request) {
        return client.call(HttpMethod.POST, "/api/admin/well-known", request, Void.class).status();
    }

    @Override
    public int adminUpdateWellKnownFile(long id, WellKnownFileRequest request) {
        return client.call(HttpMethod.PUT, "/api/admin/well-known/{id}", request, Void.class, id).status();
    }

    @Override
    public int adminDeleteWellKnownFile(long id) {
        return client.call(HttpMethod.DELETE, "/api/admin/well-known/{id}", null, Void.class, id).status();
    }

    @Override
    public List<MetaTagDto> metaTags() {
        ApiClient.Result<MetaTagDto[]> result = client.getAnonymous("/api/meta-tags", MetaTagDto[].class);
        return result.status() == 200 && result.body() != null ? List.of(result.body()) : null;
    }

    @Override
    public int adminCreateMetaTag(MetaTagRequest request) {
        return client.call(HttpMethod.POST, "/api/admin/meta-tags", request, Void.class).status();
    }

    @Override
    public int adminUpdateMetaTag(long id, MetaTagRequest request) {
        return client.call(HttpMethod.PUT, "/api/admin/meta-tags/{id}", request, Void.class, id).status();
    }

    @Override
    public int adminDeleteMetaTag(long id) {
        return client.call(HttpMethod.DELETE, "/api/admin/meta-tags/{id}", null, Void.class, id).status();
    }

    @Override
    public int adminDeleteDataRow(String repo, String id) {
        return client.call(HttpMethod.DELETE, "/api/admin/data/{repo}/{id}", null, Void.class, repo, id).status();
    }

    /** No error when it went through; a 4xx/5xx without a list becomes one error naming the status. */
    private static List<TwitchatValidationError> errors(ApiClient.Result<TwitchatValidationError[]> result) {
        if (result.ok()) return List.of();
        if (result.body() != null) return List.of(result.body());
        return List.of(new TwitchatValidationError(-1, "", "HTTP " + result.status()));
    }

    @Override
    public NotificationSnapshotDto notificationSnapshot() {
        return client.get("/api/notifications/snapshot", NotificationSnapshotDto.class);
    }

    @Override
    public AdminPage<NotificationDto> notifications(int page) {
        return client.get("/api/notifications?page={page}&size=20", NOTIFICATIONS, page);
    }

    @Override
    public int markNotificationRead(UUID id) {
        return client.call(HttpMethod.POST, "/api/notifications/{id}/read", null, Void.class, id).status();
    }

    @Override
    public int markAllNotificationsRead() {
        return client.call(HttpMethod.POST, "/api/notifications/read-all", null, Void.class).status();
    }

    @Override
    public List<String> seenFeatures() {
        return client.get("/api/me/seen-features", FEATURE_KEYS);
    }

    @Override
    public int markFeatureSeen(String key) {
        return client.call(HttpMethod.PUT, "/api/me/seen-features/{key}", null, Void.class, key).status();
    }

    @Override
    public AdminPage<AdminNotificationDto> adminNotifications(int page) {
        return client.get("/api/admin/notifications?page={page}&size=20", ADMIN_NOTIFICATIONS, page);
    }

    @Override
    public int adminCreateNotification(AdminNotificationCreateBody body) {
        return client.call(HttpMethod.POST, "/api/admin/notifications", body, Void.class).status();
    }

    @Override
    public int adminDeleteNotification(UUID id) {
        return client.call(HttpMethod.DELETE, "/api/admin/notifications/{id}", null, Void.class, id).status();
    }

    @Override
    public ApiClient.Result<String> adminPreviewNotification(String body) {
        ApiClient.Result<NotificationPreviewDto> result = client.call(HttpMethod.POST, "/api/admin/notifications/preview",
                new NotificationPreviewRequest(body), NotificationPreviewDto.class);
        if (!result.ok()) return new ApiClient.Result<>(result.status(), null);
        return result.body() != null && result.body().bodyHtml() != null
                ? new ApiClient.Result<>(result.status(), result.body().bodyHtml())
                : new ApiClient.Result<>(502, null);
    }

    @Override
    public ApiClient.Result<String> adminImpersonate(String username) {
        ApiClient.Result<ImpersonateResponse> result = client.call(HttpMethod.POST, "/api/admin/impersonate",
                new ImpersonateRequest(username), ImpersonateResponse.class);
        if (!result.ok()) return new ApiClient.Result<>(result.status(), null);
        return result.body() != null && result.body().token() != null && !result.body().token().isBlank()
                ? new ApiClient.Result<>(result.status(), result.body().token())
                : new ApiClient.Result<>(502, null);
    }

    @Override
    public List<DtddProposalDto> adminDtddProposals(String status) {
        return client.get("/api/admin/dtdd-mapping/proposals?status={status}", DTDD_PROPOSALS, status);
    }

    @Override
    public int adminApproveDtddProposal(UUID id) {
        return client.call(HttpMethod.POST, "/api/admin/dtdd-mapping/proposals/{id}/approve", null, Void.class, id).status();
    }

    @Override
    public int adminRejectDtddProposal(UUID id, String reason) {
        Map<String, String> body = reason == null || reason.isBlank() ? Map.of() : Map.of("reason", reason);
        return client.call(HttpMethod.POST, "/api/admin/dtdd-mapping/proposals/{id}/reject", body, Void.class, id).status();
    }

    @Override
    public List<GroupSummaryDto> adminGroups() {
        return client.get("/api/admin/groups", GROUPS);
    }

    @Override
    public int adminCreateGroup(String key, String name, String description) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("key", key);
        body.put("name", name);
        body.put("description", description);
        return client.call(HttpMethod.POST, "/api/admin/groups", body, Void.class).status();
    }

    @Override
    public int adminRenameGroup(UUID id, String name, String description) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("description", description);
        return client.call(HttpMethod.POST, "/api/admin/groups/{id}/rename", body, Void.class, id).status();
    }

    @Override
    public int adminDeleteGroup(UUID id) {
        return client.call(HttpMethod.POST, "/api/admin/groups/{id}/delete", null, Void.class, id).status();
    }

    @Override
    public int adminAddGroupMember(UUID id, String twitchUsername) {
        return client.call(HttpMethod.POST, "/api/admin/groups/{id}/members", Map.of("twitchUsername", twitchUsername), Void.class, id).status();
    }

    @Override
    public List<ExperimentSummaryDto> adminExperiments() {
        return client.get("/api/admin/experiments", EXPERIMENTS);
    }

    @Override
    public ExperimentSettingsDto adminExperiment(UUID id) {
        return client.get("/api/admin/experiments/{id}/settings", ExperimentSettingsDto.class, id);
    }

    @Override
    public List<FlagKeyDto> adminFlagKeys() {
        return client.get("/api/admin/flags", FLAG_KEYS);
    }

    @Override
    public int adminExperimentAction(UUID id, String action) {
        return client.call(HttpMethod.POST, "/api/admin/experiments/{id}/{action}", null, Void.class, id, action).status();
    }

    @Override
    public int adminExperimentWeights(UUID id, Map<UUID, Integer> weights) {
        return client.call(HttpMethod.POST, "/api/admin/experiments/{id}/variants/weights", weights, Void.class, id).status();
    }

    @Override
    public ExperimentRebalanceDto adminExperimentWeightsPreview(UUID id, Map<UUID, Integer> weights) {
        ApiClient.Result<ExperimentRebalanceDto> result = client.call(HttpMethod.POST,
                "/api/admin/experiments/{id}/variants/weights/preview", weights, ExperimentRebalanceDto.class, id);
        return result.ok() ? result.body() : null;
    }

    @Override
    public int adminAddExperimentRule(UUID id, ExperimentRuleRequest rule) {
        return client.call(HttpMethod.POST, "/api/admin/experiments/{id}/rules", rule, Void.class, id).status();
    }

    @Override
    public int adminDeleteExperimentRule(UUID id, UUID ruleId) {
        return client.call(HttpMethod.POST, "/api/admin/experiments/{id}/rules/{ruleId}/delete", null, Void.class, id, ruleId).status();
    }

    @Override
    public int adminAddExperimentOverride(UUID id, ExperimentOverrideRequest override) {
        return client.call(HttpMethod.POST, "/api/admin/experiments/{id}/overrides", override, Void.class, id).status();
    }

    @Override
    public int adminDeleteExperimentOverride(UUID id, UUID overrideId) {
        return client.call(HttpMethod.POST, "/api/admin/experiments/{id}/overrides/{overrideId}/delete", null, Void.class, id, overrideId).status();
    }

    @Override
    public ExperimentAssignmentsDto adminExperimentAssignments(UUID id, UUID variantId, String q, int page) {
        return client.get("/api/admin/experiments/{id}/assignments?variant={variant}&q={q}&page={page}",
                ExperimentAssignmentsDto.class, id, variantId == null ? "" : variantId, q == null ? "" : q, page);
    }

    @Override
    public int adminMoveExperimentAssignment(UUID id, UUID userId, UUID variantId) {
        return client.call(HttpMethod.POST, "/api/admin/experiments/{id}/assignments/{userId}/variant",
                new MoveAssignmentRequest(variantId), Void.class, id, userId).status();
    }

    @Override
    public WhitelistPageDto adminWhitelist() {
        return client.get("/api/admin/whitelist", WhitelistPageDto.class);
    }

    @Override
    public int adminToggleWhitelist() {
        return client.call(HttpMethod.POST, "/api/admin/whitelist/toggle", null, Void.class).status();
    }

    @Override
    public int adminAddWhitelist(String twitchId) {
        return client.call(HttpMethod.POST, "/api/admin/whitelist/add", Map.of("twitchId", twitchId), Void.class).status();
    }

    @Override
    public int adminDeleteWhitelist(String twitchId) {
        return client.call(HttpMethod.POST, "/api/admin/whitelist/{id}/delete", null, Void.class, twitchId).status();
    }

    @Override
    public int adminToggleInvites() {
        return client.call(HttpMethod.POST, "/api/admin/whitelist/invite/toggle", null, Void.class).status();
    }

    @Override
    public int adminSaveInviteSettings(Integer globalMaxMembers, Integer defaultMaxUses, boolean defaultCanReinvite) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("globalMaxMembers", globalMaxMembers);
        body.put("defaultMaxUses", defaultMaxUses);
        body.put("defaultCanReinvite", defaultCanReinvite);
        return client.call(HttpMethod.POST, "/api/admin/whitelist/invite/settings", body, Void.class).status();
    }

    @Override
    public int adminSaveInviteQuota(UUID inviteId, Integer maxUses, Boolean canReinvite) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("maxUses", maxUses);
        body.put("canReinvite", canReinvite);
        return client.call(HttpMethod.POST, "/api/admin/whitelist/invite/{id}/quota", body, Void.class, inviteId).status();
    }

    @Override
    public AdminMemberSummaryDto adminMember(UUID id) {
        return client.get("/api/admin/members/{id}", AdminMemberSummaryDto.class, id);
    }

    @Override
    public MemberTargetingDto adminMemberTargeting(UUID id) {
        return client.get("/api/admin/members/{id}/targeting", MemberTargetingDto.class, id);
    }

    @Override
    public int adminSetMemberFlag(UUID id, String key, String value) {
        // a body map (not Map.of): the value is null for a flag set without one
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("key", key);
        body.put("value", value);
        return client.call(HttpMethod.POST, "/api/admin/members/{id}/flags", body, Void.class, id).status();
    }

    @Override
    public int adminDeleteMemberFlag(UUID id, String key) {
        // the key is a URI variable, so a "/", "%" or space in it is encoded rather than read as part of the path
        return client.call(HttpMethod.POST, "/api/admin/members/{id}/flags/{key}/delete", null, Void.class, id, key).status();
    }

    @Override
    public int adminAddMemberToGroup(UUID id, String groupKey) {
        return client.call(HttpMethod.POST, "/api/admin/members/{id}/groups", Map.of("groupKey", groupKey), Void.class, id).status();
    }

    @Override
    public int adminRemoveMemberFromGroup(UUID id, UUID groupId) {
        return client.call(HttpMethod.POST, "/api/admin/members/{id}/groups/{groupId}/delete", null, Void.class, id, groupId).status();
    }

    @Override
    public int adminBroadcast(BroadcastRequestDto body) {
        return client.call(HttpMethod.POST, "/api/admin/broadcast", body, Void.class).status();
    }

    @Override
    public UserInfoResponse accountInfo() {
        return client.get("/api/auth/validate", UserInfoResponse.class);
    }

    @Override
    public int validateSession() {
        return client.call(HttpMethod.GET, "/api/auth/validate", null, Void.class).status();
    }

    @Override
    public int accountDeletionDelayDays() {
        AppConfigResponse config = client.get("/api/config/app", AppConfigResponse.class);
        return config == null || config.deletionDelayDays() <= 0 ? DEFAULT_DELETION_DELAY_DAYS : config.deletionDelayDays();
    }

    @Override
    public int deleteAccount(String username, String confirmUsername) {
        return client.call(HttpMethod.POST, "/api/channels/{username}/settings/delete-account",
                new DeleteAccountRequest(confirmUsername), Void.class, username).status();
    }

    @Override
    public int cancelAccountDeletion(String username) {
        return client.call(HttpMethod.POST, "/api/channels/{username}/settings/cancel-deletion", null, Void.class,
                username).status();
    }

    @Override
    public InvitePageData invitePage() {
        return client.get("/api/invite", InvitePageData.class);
    }

    @Override
    public int regenerateInvite() {
        return client.call(HttpMethod.POST, "/api/invite/regenerate", null, Void.class).status();
    }

    @Override
    public ApiKeysPageDto adminApiKeys(ApiKeyProvider provider) {
        return switch (provider) {
            case STEAM -> {
                SteamKeysPageData page = client.get("/api/admin/{path}", SteamKeysPageData.class, provider.apiPath());
                yield page == null ? null : ApiKeysPageDto.of(page);
            }
            case DTDD -> {
                DtddKeysPageData page = client.get("/api/admin/{path}", DtddKeysPageData.class, provider.apiPath());
                yield page == null ? null : ApiKeysPageDto.of(page);
            }
        };
    }

    @Override
    public int adminAddApiKey(ApiKeyProvider provider, String apiKey) {
        return client.call(HttpMethod.POST, "/api/admin/{path}/add", Map.of("apiKey", apiKey), Void.class, provider.apiPath()).status();
    }

    @Override
    public int adminDeleteApiKey(ApiKeyProvider provider, String keyId) {
        return client.call(HttpMethod.POST, "/api/admin/{path}/delete", Map.of("keyId", keyId), Void.class, provider.apiPath()).status();
    }

    @Override
    public int adminRefreshApiKeys(ApiKeyProvider provider) {
        return client.call(HttpMethod.POST, "/api/admin/{path}/refresh", null, Void.class, provider.apiPath()).status();
    }

    private static final String MINECRAFT_ACCOUNTS = "/api/admin/minecraft-accounts";

    @Override
    public List<AccountDto> adminMinecraftAccounts() {
        // a 404 (Minecraft disabled in catapult-api) is null, like any failed read
        AccountDto[] accounts = client.get(MINECRAFT_ACCOUNTS, AccountDto[].class);
        return accounts == null ? null : List.of(accounts);
    }

    @Override
    public int adminPatchMinecraftAccount(UUID id, Map<String, Object> patch) {
        return client.call(HttpMethod.PATCH, MINECRAFT_ACCOUNTS + "/{id}", patch, Void.class, id).status();
    }

    @Override
    public int adminDeleteMinecraftAccount(UUID id) {
        return client.call(HttpMethod.DELETE, MINECRAFT_ACCOUNTS + "/{id}", null, Void.class, id).status();
    }

    @Override
    public MinecraftDeviceCodeStart adminMinecraftDeviceCode() {
        // read as a map: the code on 200 (snake_case, Microsoft's own fields), {message} on a refusal
        @SuppressWarnings("rawtypes")
        ApiClient.Result<Map> result = client.call(HttpMethod.POST, MINECRAFT_ACCOUNTS + "/device-code", null, Map.class);
        Map<?, ?> body = result.body() == null ? Map.of() : result.body();
        if (result.status() == 200) {
            if (!(body.get("device_code") instanceof String deviceCode) || deviceCode.isBlank()) {
                return new MinecraftDeviceCodeStart(502, null, null);
            }
            return new MinecraftDeviceCodeStart(200, new MinecraftDeviceCodeDto(deviceCode, text(body.get("user_code")),
                    text(body.get("verification_uri")), body.get("interval") instanceof Number n ? n.intValue() : 0,
                    body.get("expires_in") instanceof Number n ? n.intValue() : 0), null);
        }
        return new MinecraftDeviceCodeStart(result.ok() ? 502 : result.status(), null, text(body.get("message")));
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    @Override
    public ApiClient.Result<Map<String, Object>> adminCreateMinecraftAccount(String deviceCode, String label) {
        Map<String, String> body = label == null ? Map.of("deviceCode", deviceCode) : Map.of("deviceCode", deviceCode, "label", label);
        return mapResult(client.call(HttpMethod.POST, MINECRAFT_ACCOUNTS, body, Map.class));
    }

    @Override
    public ApiClient.Result<Map<String, Object>> adminReauthMinecraftAccount(UUID id, String deviceCode) {
        return mapResult(client.call(HttpMethod.POST, MINECRAFT_ACCOUNTS + "/{id}/reauth", Map.of("deviceCode", deviceCode), Map.class, id));
    }

    @SuppressWarnings("unchecked")
    private static ApiClient.Result<Map<String, Object>> mapResult(@SuppressWarnings("rawtypes") ApiClient.Result<Map> result) {
        return new ApiClient.Result<>(result.status(), (Map<String, Object>) result.body());
    }

    @Override
    public List<TwDefinitionDto> adminTws() {
        return client.get("/api/admin/tw", TWS);
    }

    @Override
    public TwDefinitionDto adminTw(String id) {
        return client.get("/api/admin/tw/{id}", TwDefinitionDto.class, id);
    }

    @Override
    public int adminCreateTw(String id, String label, int sortOrder) {
        return client.call(HttpMethod.POST, "/api/admin/tw", new AdminTwCreateBody(id, label, null, sortOrder), Void.class).status();
    }

    @Override
    public int adminRebuildTws(TwRebuild mode) {
        // the route is the enum's own constant, never user input
        return client.call(HttpMethod.POST, "/api/admin/tw/" + mode.apiPath(), null, Void.class).status();
    }

    @Override
    public List<String> adminTwKeywords(String id) {
        return client.get("/api/admin/tw/{id}/steam-keywords", new ParameterizedTypeReference<List<String>>() {}, id);
    }

    @Override
    public int adminAddTwKeyword(String id, String keyword) {
        return client.call(HttpMethod.POST, "/api/admin/tw/{id}/steam-keywords", new SteamKeywordBody(keyword), Void.class, id).status();
    }

    @Override
    public int adminRemoveTwKeyword(String id, String keyword) {
        // free text: a URI variable, encoded as one segment
        return client.call(HttpMethod.DELETE, "/api/admin/tw/{id}/steam-keywords/{keyword}", null, Void.class, id, keyword).status();
    }

    @Override
    public TwKeywordTestDto adminTestTwKeyword(String id, String appId, String draftKeyword) {
        var result = client.call(HttpMethod.POST, "/api/admin/tw/{id}/steam-keywords/test",
                new SteamSignalTestBody(appId, draftKeyword), TwKeywordTestDto.class, id);
        return result.ok() ? result.body() : null;
    }

    @Override
    public fr.enimaloc.catapult.common.dto.HelpContent help(String cardId, java.util.Locale locale) {
        return client.getLocalized("/api/help/{cardId}", fr.enimaloc.catapult.common.dto.HelpContent.class, locale, cardId);
    }

    @Override
    public String steamConnectUrl() {
        if (!ApiClient.hasSession()) return null;
        SteamStartResponse start = client.get("/api/connect/steam/start", SteamStartResponse.class);
        return start == null ? null : start.redirectUrl();
    }

    @Override
    public int steamDisconnect() {
        return client.call(HttpMethod.POST, "/api/connect/steam/disconnect", null, Void.class).status();
    }

    @Override
    public List<ChangelogSection> changelog() {
        ApiClient.Result<ChangelogSection[]> result = client.getAnonymous("/api/changelog", ChangelogSection[].class);
        return result.ok() && result.body() != null ? List.of(result.body()) : null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public boolean apiAvailable() {
        ApiClient.Result<Map> health = client.getAnonymous("/api/health", Map.class);
        return health.ok() && health.body() != null && "UP".equals(health.body().get("status"));
    }
}
