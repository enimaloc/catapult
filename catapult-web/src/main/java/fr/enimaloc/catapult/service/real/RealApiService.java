package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.common.dto.DegradedStatusDto;
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
import fr.enimaloc.catapult.common.dto.admin.AudienceCountDto;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountRequest;
import fr.enimaloc.catapult.common.dto.admin.NotificationSentDto;
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
import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkOrderRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
import fr.enimaloc.catapult.common.dto.admin.MinecraftDeviceCodeDto;
import fr.enimaloc.catapult.common.dto.admin.DtddKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.SteamKeysPageData;
import fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.common.dto.admin.TwitchCategoryDto;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.BroadcastRequestDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannel;
import fr.enimaloc.catapult.common.dto.notification.NotificationChannelsDto;
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
import fr.enimaloc.catapult.common.dto.channel.DtddProposalRequest;
import fr.enimaloc.catapult.common.dto.channel.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.ObsSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.RaidSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.TwitchatBranchRequest;
import fr.enimaloc.catapult.common.dto.channel.SaveBody;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenRequest;
import fr.enimaloc.catapult.common.dto.channel.SteamTokenSharingRequest;
import fr.enimaloc.catapult.common.dto.channel.TwEnabledBody;
import fr.enimaloc.catapult.common.dto.channel.TwSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.UpdateBindingRequest;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
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
import fr.enimaloc.catapult.common.dto.account.ApiTokenCreatedDto;
import fr.enimaloc.catapult.common.dto.account.ApiTokenStatusDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingDto;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingRequest;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingSaveResult;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingsStateDto;
import fr.enimaloc.catapult.service.ActivationOutcome;
import fr.enimaloc.catapult.service.CustomBindingSave;
import tools.jackson.databind.JsonNode;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.service.http.ApiError;
import fr.enimaloc.catapult.service.http.ApiErrorText;
import org.springframework.context.i18n.LocaleContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;

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
    private static final ParameterizedTypeReference<AdminPage<fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto>> DEBUG_EXCHANGES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<String>> DEBUG_SERVICES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AdminPage<GameMappingDto>> GAME_MAPPINGS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AdminPage<DetectedGameDto>> MISSING_GAMES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<DetectedGameDto>> DETECTED_GAMES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<TwitchCategoryDto>> CATEGORIES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<DtddProposalDto>> DTDD_PROPOSALS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<TwDefinitionDto>> TWS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto>> ENABLED_COMMANDS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<GroupSummaryDto>> GROUPS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<ExperimentSummaryDto>> EXPERIMENTS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<FlagKeyDto>> FLAG_KEYS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AdminPage<AdminNotificationDto>> ADMIN_NOTIFICATIONS = new ParameterizedTypeReference<>() {};

    private static final tools.jackson.databind.json.JsonMapper SAVE_JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final ApiClient client;
    private final ApiErrorText errorText;
    private final ChannelIds channelIds;

    @Override
    public TokenResponse exchangeCode(String code) {
        return client.post("/api/internal/auth/exchange?code={code}", TokenResponse.class, code);
    }

    @Override
    public ChannelListResponse channelList() {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/channels", ChannelListResponse.class, me);
    }

    @Override
    public ChannelPageData channelPage(String username, int page, String status, String source) {
        return readChannel(username, id -> {
            StringBuilder path = new StringBuilder("/api/internal/users/{userId}/dashboard?page={page}");
            List<Object> vars = new ArrayList<>(List.of(id, page));
            if (status != null && !status.isBlank()) {
                path.append("&status={status}");
                vars.add(status);
            } else if (source != null && !source.isBlank()) {
                path.append("&source={source}");
                vars.add(source);
            }
            return client.getResult(path.toString(), ChannelPageData.class, vars.toArray());
        });
    }

    @Override
    public int toggleBot(String username) {
        return onChannel(username, id -> client.postVoid("/api/internal/users/{userId}/settings/bot/toggle", null, id),
                status -> status);
    }

    @Override
    public int recheckGame(String username) {
        return onChannel(username, id -> client.postVoid("/api/internal/users/{userId}/game/recheck", null, id),
                status -> status);
    }

    @Override
    public int cclToggle(String username, String bindingId, boolean enabled) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/bindings/{bindingId}/ccl-enabled",
                new CclToggleRequest(enabled), id, bindingId), status -> status);
    }

    @Override
    public int ignoredToggle(String username, String bindingId, boolean ignored) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/bindings/{bindingId}/ignored",
                new IgnoredToggleRequest(ignored), id, bindingId), status -> status);
    }

    @Override
    public int deleteBinding(String username, String bindingId) {
        return onChannel(username, id -> client.delete("/api/internal/users/{userId}/bindings/{bindingId}", id, bindingId),
                status -> status);
    }

    @Override
    public Object searchGames(String username, String q) {
        return readChannel(username, id -> client.getResult("/api/internal/users/{userId}/games?q={q}", Object.class, id, q));
    }

    @Override
    public int updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, Set<String> ccls) {
        return onChannel(username, id -> client.patchVoid("/api/internal/users/{userId}/bindings/{bindingId}",
                new UpdateBindingRequest(twitchGameId, twitchGameName, ccls), id, bindingId), status -> status);
    }

    @Override
    public int saveTws(String bindingId, Set<String> tws) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.putVoid("/api/internal/users/{userId}/bindings/{bindingId}/tws", new SaveBody(tws), me, bindingId);
    }

    @Override
    public int resetTws(String bindingId) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.delete("/api/internal/users/{userId}/bindings/{bindingId}/tws", me, bindingId);
    }

    @Override
    public int toggleTwEnabled(String bindingId, boolean enabled) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.putVoid("/api/internal/users/{userId}/bindings/{bindingId}/tw-enabled", new TwEnabledBody(enabled), me,
                bindingId);
    }

    @Override
    public int saveSteamToken(String username, String token, boolean shared) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/steam-personal-token",
                new SteamTokenRequest(token, shared), id), status -> status);
    }

    @Override
    public int steamTokenSharing(String username, boolean shared) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/steam-personal-token/sharing",
                new SteamTokenSharingRequest(shared), id), status -> status);
    }

    @Override
    public int deleteSteamToken(String username) {
        return onChannel(username, id -> client.delete("/api/internal/users/{userId}/settings/steam-personal-token", id),
                status -> status);
    }

    @Override
    public int refreshSteamProfileCache(String username) {
        return onChannel(username, id -> client.postVoid("/api/internal/users/{userId}/steam-profile/refresh", null, id),
                status -> status);
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/links/minecraft", LinkStateResponse.class, me);
    }

    @Override
    public int minecraftEnroll(String username, String name) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.putVoid("/api/internal/users/{userId}/links/minecraft", Map.of("name", name), me);
    }

    @Override
    public int minecraftSync(String username) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.postVoid("/api/internal/users/{userId}/links/minecraft/sync", null, me);
    }

    @Override
    public int minecraftDisconnect(String username) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.delete("/api/internal/users/{userId}/links/minecraft", me);
    }

    @Override
    public int xboxDisconnect(String username) {
        return onChannel(username, id -> client.delete("/api/internal/users/{userId}/oauth-tokens/{provider}", id, "xbox"),
                status -> status);
    }

    @Override
    public TwitchatWidgetConfig obsConnection() {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/obs", TwitchatWidgetConfig.class, me);
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
        return readChannel(username, id -> client.getResult("/api/internal/users/{userId}/settings/twitchat",
                TwitchatSettingsResponse.class, id));
    }

    @Override
    public ApiClient.Result<TwitchatSettingsResponse> regenerateTwitchatWidget(String username) {
        // The answer carries the new token: never logged.
        ApiClient.Result<TwitchatSettingsResponse> result = onChannel(username, id -> client.call(HttpMethod.POST,
                "/api/internal/users/{userId}/settings/twitchat/regenerate", null, TwitchatSettingsResponse.class, id),
                status -> new ApiClient.Result<>(status, null));
        return result.ok() ? result : new ApiClient.Result<>(result.status(), null);
    }

    @Override
    public int saveObsSettings(String username, boolean enabled, String host, Integer port, String password) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/obs",
                new ObsSettingsRequest(enabled, host, port, password), id), status -> status);
    }

    @Override
    public int saveTwitchatBranch(String username, String branch) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/twitchat/branch",
                new TwitchatBranchRequest(branch), id), status -> status);
    }

    @Override
    public int saveCclSettings(String username, boolean enabled, Set<String> blockedCcls) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/ccl",
                new CclSettingsRequest(enabled, blockedCcls), id), status -> status);
    }

    @Override
    public int saveTwSettings(String username, boolean enabled, Set<String> blockedTws) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/tws",
                new TwSettingsRequest(enabled, blockedTws), id), status -> status);
    }

    @Override
    public UserSettingsDto channelSettings(String username) {
        return readChannel(username, id -> client.getResult("/api/internal/users/{userId}/settings", UserSettingsDto.class, id));
    }

    @Override
    public BindingDto currentBinding(String username) {
        return readChannel(username, id -> client.getResult("/api/internal/users/{userId}/bindings/current", BindingDto.class, id));
    }

    @Override
    public List<fr.enimaloc.catapult.common.dto.chatcommand.EnabledChatCommandDto> enabledChatCommands() {
        UUID me = me();
        if (me == null) return List.of();
        var commands = client.get("/api/internal/users/{userId}/chat-commands/enabled", ENABLED_COMMANDS, me);
        return commands == null ? List.of() : commands;
    }

    @Override
    public int saveNoGameSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/no-game",
                new NoGameSettingsRequest(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd),
                id), status -> status);
    }

    @Override
    public int saveRaidSettings(String username, Integer restoreGameMinutes) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/raid",
                new RaidSettingsRequest(restoreGameMinutes), id), status -> status);
    }

    @Override
    public int saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls) {
        return onChannel(username, id -> client.putVoid("/api/internal/users/{userId}/settings/incomplete-fallback",
                new IncompleteFallbackRequest(twitchGameId, twitchGameName, ccls), id), status -> status);
    }

    @Override
    public DtddMappingStatusDto dtddMappingStatus(String username) {
        return readChannel(username, id -> client.getResult("/api/internal/users/{userId}/dtdd-mapping",
                DtddMappingStatusDto.class, id));
    }

    @Override
    public SearchResponse dtddSearch(String q) {
        return client.get("/api/internal/dtdd-games?q={q}", SearchResponse.class, q);
    }

    @Override
    public int dtddValidate(String igdbId) {
        return client.postVoid("/api/internal/admin/dtdd-mappings/{igdbId}/validate", null, igdbId);
    }

    @Override
    public int dtddPropose(String igdbId, Long dtddId, String reason) {
        return client.postVoid("/api/internal/dtdd-mappings/{igdbId}/proposals", new DtddProposalRequest(dtddId, reason), igdbId);
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
        Map<String, List<TwitchatEventActionDto>> events = readChannel(username,
                id -> client.getResult("/api/internal/users/{userId}/twitchat-events", EVENTS, id));
        return events == null ? Map.of() : events;
    }

    @Override
    public List<TwitchatValidationError> saveTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        return errors(onChannel(username, id -> client.call(HttpMethod.PUT,
                "/api/internal/users/{userId}/twitchat-events/{eventType}/actions", payloads, Void.class, id, eventType),
                status -> new ApiClient.Result<>(status, null)));
    }

    @Override
    public List<TwitchatValidationError> testTwitchatEvent(String username, String eventType, List<TwitchatEventActionDto> payloads) {
        return errors(onChannel(username, id -> client.call(HttpMethod.POST,
                "/api/internal/users/{userId}/twitchat-events/{eventType}/test", payloads, Void.class, id, eventType),
                status -> new ApiClient.Result<>(status, null)));
    }

    @Override
    public boolean executeTwitchatAction(String token) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = client.post("/api/twitchat/actions/{token}", Map.class, token);
        return result != null && "EXECUTED".equals(result.get("result"));
    }

    @Override
    public AdminMembersPage adminMembers(String q, int page) {
        return client.get("/api/internal/admin/members?page={page}&q={q}", AdminMembersPage.class, page, q == null ? "" : q);
    }

    @Override
    public List<AdminMemberDto> adminSearchMembers(String q) {
        return client.get("/api/internal/admin/members/search?q={q}", MEMBERS, q == null ? "" : q);
    }

    @Override
    public fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto memberDebug(UUID memberId) {
        return client.get("/api/internal/admin/members/{id}/debug", fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto.class, memberId);
    }

    @Override
    public int enableMemberDebug(UUID memberId, boolean revealSecrets) {
        return client.postVoid("/api/internal/admin/members/{id}/debug/enable",
                new fr.enimaloc.catapult.common.dto.admin.DebugEnableRequest(revealSecrets), memberId);
    }

    @Override
    public int disableMemberDebug(UUID memberId) {
        return client.postVoid("/api/internal/admin/members/{id}/debug/disable", null, memberId);
    }

    @Override
    public int clearMemberDebug(UUID memberId) {
        return client.delete("/api/internal/admin/members/{id}/debug/exchanges", memberId);
    }

    @Override
    public AdminPage<fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto> memberDebugExchanges(
            UUID memberId, fr.enimaloc.catapult.service.DebugExchangeFilter filter, int page) {
        // Unset filters go as empty values, which catapult-api reads as absent
        return client.get("/api/internal/admin/members/{id}/debug/exchanges?service={service}&direction={direction}&outcome={outcome}&from={from}&to={to}&page={page}&size=50",
                DEBUG_EXCHANGES, memberId, text(filter.service()), text(filter.direction()), text(filter.outcome()),
                filter.from() == null ? "" : filter.from().toString(), filter.to() == null ? "" : filter.to().toString(), page);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    @Override
    public fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto memberDebugExchange(UUID memberId, UUID exchangeId) {
        return client.get("/api/internal/admin/members/{id}/debug/exchanges/{exchangeId}",
                fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto.class, memberId, exchangeId);
    }

    @Override
    public List<String> memberDebugServices(UUID memberId) {
        List<String> services = client.get("/api/internal/admin/members/{id}/debug/services", DEBUG_SERVICES, memberId);
        return services == null ? List.of() : services;
    }

    @Override
    public Boolean myDebugActive() {
        UUID me = me();
        if (me == null) return null;
        fr.enimaloc.catapult.common.dto.MyDebugDto debug = client.get("/api/internal/users/{userId}/debug", fr.enimaloc.catapult.common.dto.MyDebugDto.class, me);
        return debug == null ? null : debug.active();
    }

    @Override
    public int adminToggleMemberBot(UUID memberId) {
        return client.postVoid("/api/internal/admin/members/{id}/bot/toggle", null, memberId);
    }

    @Override
    public int adminUnlinkMemberSteam(UUID memberId) {
        return client.delete("/api/internal/admin/members/{id}/links/steam", memberId);
    }

    @Override
    public int adminUnlinkMemberTwitch(UUID memberId) {
        return client.delete("/api/internal/admin/members/{id}/links/twitch", memberId);
    }

    @Override
    public int adminPromoteMemberToSystem(UUID memberId) {
        return client.postVoid("/api/internal/admin/members/{id}/promote-to-system", null, memberId);
    }

    @Override
    public int adminDeleteMember(UUID memberId) {
        return client.delete("/api/internal/admin/members/{id}", memberId);
    }

    @Override
    public int adminMigrateMember(UUID memberId, UUID targetId, boolean settings, boolean getters, boolean bindings) {
        return client.postVoid("/api/internal/admin/members/{id}/migrate", new MigrateRequest(targetId, settings, getters, bindings), memberId);
    }

    @Override
    public AdminCclPage adminCcl() {
        return client.get("/api/internal/admin/ccls", AdminCclPage.class);
    }

    @Override
    public int adminRefreshCcl() {
        return client.call(HttpMethod.POST, "/api/internal/admin/ccls/refresh", null, Void.class).status();
    }

    @Override
    public int adminSaveCclMappings(String cclId, Set<Long> igdbDescriptorIds) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/ccls/{id}/mappings",
                new SaveMappingsRequest(igdbDescriptorIds), Void.class, cclId).status();
    }

    @Override
    public List<CacheSummaryDto> adminCaches() {
        CacheSummaryDto[] caches = client.get("/api/internal/admin/caches", CacheSummaryDto[].class);
        return caches == null ? null : List.of(caches);
    }

    @Override
    public AdminPage<CacheEntryDto> adminCacheEntries(String name, String q, int page) {
        return client.get("/api/internal/admin/caches/{name}?q={q}&page={page}", CACHE_ENTRIES, name, q == null ? "" : q, page);
    }

    @Override
    public CacheEntryDetailDto adminCacheEntry(String name, String key) {
        return client.get("/api/internal/admin/caches/{name}/entry?key={key}", CacheEntryDetailDto.class, name, key);
    }

    @Override
    public int adminDeleteCacheEntry(String name, String key) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/caches/{name}?key={key}", null, Void.class, name, key).status();
    }

    @Override
    public List<RepoSummaryDto> adminDataRepos() {
        RepoSummaryDto[] repos = client.get("/api/internal/admin/data", RepoSummaryDto[].class);
        return repos == null ? null : List.of(repos);
    }

    @Override
    public AdminPage<Map<String, Object>> adminDataRows(String repo, String q, int page) {
        return client.get("/api/internal/admin/data/{repo}?q={q}&page={page}", DATA_ROWS, repo, q == null ? "" : q, page);
    }

    @Override
    public Map<String, Object> adminDataRow(String repo, String id) {
        return client.get("/api/internal/admin/data/{repo}/{id}", DATA_ROW, repo, id);
    }

    @Override
    public int adminCreateDataRow(String repo, Map<String, String> fields) {
        return client.call(HttpMethod.POST, "/api/internal/admin/data/{repo}", fields, Void.class, repo).status();
    }

    @Override
    public int adminUpdateDataRow(String repo, String id, Map<String, String> fields) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/data/{repo}/{id}", fields, Void.class, repo, id).status();
    }

    @Override
    public WellKnownContent wellKnown(WellKnownLocation location, String path) {
        ApiClient.Result<WellKnownResponseDto> result = client.getAnonymous(
                "/api/internal/well-known-files?location={location}&path={path}", WellKnownResponseDto.class, location.name(), path);
        if (result.status() == 200 && result.body() == null) return new WellKnownContent(502, null);
        return new WellKnownContent(result.status(), result.body());
    }

    @Override
    public String adminWellKnownSchema(String path) {
        return client.get("/api/internal/admin/well-known-files/schema?path={path}", String.class, path);
    }

    @Override
    public List<WellKnownFileDto> adminWellKnownFiles() {
        return client.get("/api/internal/admin/well-known-files", WELL_KNOWN_FILES);
    }

    @Override
    public WellKnownFileDetailDto adminWellKnownFile(long id) {
        return client.get("/api/internal/admin/well-known-files/{id}", WellKnownFileDetailDto.class, id);
    }

    @Override
    public IanaRegistryDto adminIanaRegistry() {
        return client.get("/api/internal/admin/well-known-files/iana", IanaRegistryDto.class);
    }

    @Override
    public int adminCreateWellKnownFile(WellKnownFileRequest request) {
        return client.call(HttpMethod.POST, "/api/internal/admin/well-known-files", request, Void.class).status();
    }

    @Override
    public int adminUpdateWellKnownFile(long id, WellKnownFileRequest request) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/well-known-files/{id}", request, Void.class, id).status();
    }

    @Override
    public int adminDeleteWellKnownFile(long id) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/well-known-files/{id}", null, Void.class, id).status();
    }

    @Override
    public List<MetaTagDto> metaTags() {
        ApiClient.Result<MetaTagDto[]> result = client.getAnonymous("/api/internal/meta-tags", MetaTagDto[].class);
        return result.status() == 200 && result.body() != null ? List.of(result.body()) : null;
    }

    @Override
    public int adminCreateMetaTag(MetaTagRequest request) {
        return client.call(HttpMethod.POST, "/api/internal/admin/meta-tags", request, Void.class).status();
    }

    @Override
    public int adminUpdateMetaTag(long id, MetaTagRequest request) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/meta-tags/{id}", request, Void.class, id).status();
    }

    @Override
    public int adminDeleteMetaTag(long id) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/meta-tags/{id}", null, Void.class, id).status();
    }

    @Override
    public List<SocialLinkDto> socialLinks() {
        ApiClient.Result<SocialLinkDto[]> result = client.getAnonymous("/api/internal/social-links", SocialLinkDto[].class);
        return result.status() == 200 && result.body() != null ? List.of(result.body()) : null;
    }

    @Override
    public List<SocialLinkDto> adminSocialLinks() {
        ApiClient.Result<SocialLinkDto[]> result = client.getResult("/api/internal/admin/social-links", SocialLinkDto[].class);
        return result.status() == 200 && result.body() != null ? List.of(result.body()) : null;
    }

    @Override
    public int adminCreateSocialLink(SocialLinkRequest request) {
        return client.call(HttpMethod.POST, "/api/internal/admin/social-links", request, Void.class).status();
    }

    @Override
    public int adminUpdateSocialLink(long id, SocialLinkRequest request) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/social-links/{id}", request, Void.class, id).status();
    }

    @Override
    public int adminDeleteSocialLink(long id) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/social-links/{id}", null, Void.class, id).status();
    }

    @Override
    public int adminOrderSocialLinks(List<Long> ids) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/social-links/order", new SocialLinkOrderRequest(ids),
                Void.class).status();
    }

    @Override
    public int adminDeleteDataRow(String repo, String id) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/data/{repo}/{id}", null, Void.class, repo, id).status();
    }

    /**
     * No error when it went through; refused payloads are the problem's list; any other refusal is one error with
     * this app's own text for its code, else naming the status.
     */
    private List<TwitchatValidationError> errors(ApiClient.Result<Void> result) {
        if (result.ok()) return List.of();
        ApiError error = result.error();
        if (error != null && error.code() == ErrorCode.TWITCHAT_INVALID_PAYLOAD) {
            TwitchatValidationError[] listed = error.property("errors", TwitchatValidationError[].class);
            if (listed != null) return List.of(listed);
        }
        return List.of(new TwitchatValidationError(-1, "",
                errorText.text(error, LocaleContextHolder.getLocale()).orElse("HTTP " + result.status())));
    }

    @Override
    public NotificationSnapshotDto notificationSnapshot() {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/notifications/snapshot", NotificationSnapshotDto.class, me);
    }

    @Override
    public AdminPage<NotificationDto> notifications(int page) {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/notifications?page={page}&size=20", NOTIFICATIONS, me, page);
    }

    @Override
    public int markNotificationRead(UUID id) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.call(HttpMethod.POST, "/api/internal/users/{userId}/notifications/{id}/read", null, Void.class, me, id).status();
    }

    @Override
    public int markAllNotificationsRead() {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.call(HttpMethod.POST, "/api/internal/users/{userId}/notifications/read-all", null, Void.class, me).status();
    }

    @Override
    public NotificationChannelsDto notificationChannels() {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/notification-channels", NotificationChannelsDto.class, me);
    }

    @Override
    public int saveNotificationChannels(java.util.Set<NotificationChannel> channels) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.call(HttpMethod.PUT, "/api/internal/users/{userId}/notification-channels", new NotificationChannelsDto(channels), Void.class, me).status();
    }

    @Override
    public List<String> seenFeatures() {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/seen-features", FEATURE_KEYS, me);
    }

    @Override
    public int markFeatureSeen(String key) {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.call(HttpMethod.PUT, "/api/internal/users/{userId}/seen-features/{key}", null, Void.class, me, key).status();
    }

    @Override
    public AdminPage<AdminNotificationDto> adminNotifications(int page) {
        return client.get("/api/internal/admin/notifications?page={page}&size=20", ADMIN_NOTIFICATIONS, page);
    }

    @Override
    public ApiClient.Result<Integer> adminSendNotification(AdminNotificationCreateBody body) {
        ApiClient.Result<NotificationSentDto> result = client.call(HttpMethod.POST, "/api/internal/admin/notifications", body,
                NotificationSentDto.class);
        return new ApiClient.Result<>(result.status(), result.body() == null ? null : result.body().sent());
    }

    @Override
    public Long adminNotificationAudienceCount(UUID experimentId, UUID variantId) {
        ApiClient.Result<AudienceCountDto> result = client.call(HttpMethod.POST, "/api/internal/admin/notifications/audience-count",
                new AudienceCountRequest(AdminNotificationCreateBody.EXPERIMENT, experimentId, variantId), AudienceCountDto.class);
        return result.ok() && result.body() != null ? result.body().count() : null;
    }

    @Override
    public int adminDeleteNotification(UUID id) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/notifications/{id}", null, Void.class, id).status();
    }

    @Override
    public ApiClient.Result<String> adminPreviewNotification(String body) {
        ApiClient.Result<NotificationPreviewDto> result = client.call(HttpMethod.POST, "/api/internal/admin/notifications/preview",
                new NotificationPreviewRequest(body), NotificationPreviewDto.class);
        if (!result.ok()) return new ApiClient.Result<>(result.status(), null);
        return result.body() != null && result.body().bodyHtml() != null
                ? new ApiClient.Result<>(result.status(), result.body().bodyHtml())
                : new ApiClient.Result<>(502, null);
    }

    @Override
    public ApiClient.Result<String> adminImpersonate(String username) {
        ApiClient.Result<ImpersonateResponse> result = client.call(HttpMethod.POST, "/api/internal/admin/impersonations",
                new ImpersonateRequest(username), ImpersonateResponse.class);
        if (!result.ok()) return new ApiClient.Result<>(result.status(), null);
        return result.body() != null && result.body().token() != null && !result.body().token().isBlank()
                ? new ApiClient.Result<>(result.status(), result.body().token())
                : new ApiClient.Result<>(502, null);
    }

    @Override
    public List<DtddProposalDto> adminDtddProposals(String status) {
        return client.get("/api/internal/admin/dtdd-mapping-proposals?status={status}", DTDD_PROPOSALS, status);
    }

    @Override
    public int adminApproveDtddProposal(UUID id) {
        return client.call(HttpMethod.POST, "/api/internal/admin/dtdd-mapping-proposals/{id}/approve", null, Void.class, id).status();
    }

    @Override
    public int adminRejectDtddProposal(UUID id, String reason) {
        Map<String, String> body = reason == null || reason.isBlank() ? Map.of() : Map.of("reason", reason);
        return client.call(HttpMethod.POST, "/api/internal/admin/dtdd-mapping-proposals/{id}/reject", body, Void.class, id).status();
    }

    @Override
    public AdminPage<GameMappingDto> adminGameMappings(int page) {
        return client.get("/api/internal/admin/game-mappings?page={page}&size=20", GAME_MAPPINGS, page);
    }

    @Override
    public AdminPage<DetectedGameDto> adminMissingGameMappings(int page) {
        return client.get("/api/internal/admin/game-mappings/missing?page={page}&size=20", MISSING_GAMES, page);
    }

    @Override
    public List<DetectedGameDto> adminDetectedGames(String sourceType, String q) {
        return client.get("/api/internal/admin/game-mappings/detected?sourceType={sourceType}&q={q}", DETECTED_GAMES,
                sourceType, q == null ? "" : q);
    }

    @Override
    public List<TwitchCategoryDto> adminSearchCategories(String q) {
        return client.get("/api/internal/admin/game-mappings/categories?q={q}", CATEGORIES, q == null ? "" : q);
    }

    @Override
    public ApiClient.Result<GameMappingResult> adminCreateGameMapping(GameMappingRequest request) {
        return client.call(HttpMethod.POST, "/api/internal/admin/game-mappings", request, GameMappingResult.class);
    }

    @Override
    public ApiClient.Result<GameMappingResult> adminUpdateGameMapping(UUID id, GameMappingRequest request) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/game-mappings/{id}", request, GameMappingResult.class, id);
    }

    @Override
    public ApiClient.Result<GameMappingResult> adminDeleteGameMapping(UUID id, boolean applyLive) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/game-mappings/{id}?applyLive={applyLive}", null,
                GameMappingResult.class, id, applyLive);
    }

    @Override
    public List<GroupSummaryDto> adminGroups() {
        return client.get("/api/internal/admin/groups", GROUPS);
    }

    @Override
    public int adminCreateGroup(String key, String name, String description) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("key", key);
        body.put("name", name);
        body.put("description", description);
        return client.call(HttpMethod.POST, "/api/internal/admin/groups", body, Void.class).status();
    }

    @Override
    public int adminRenameGroup(UUID id, String name, String description) {
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("description", description);
        return client.call(HttpMethod.PATCH, "/api/internal/admin/groups/{id}", body, Void.class, id).status();
    }

    @Override
    public int adminDeleteGroup(UUID id) {
        return client.delete("/api/internal/admin/groups/{id}", id);
    }

    @Override
    public int adminAddGroupMember(UUID id, String twitchUsername) {
        return client.call(HttpMethod.POST, "/api/internal/admin/groups/{id}/members", Map.of("twitchUsername", twitchUsername), Void.class, id).status();
    }

    @Override
    public List<ExperimentSummaryDto> adminExperiments() {
        return client.get("/api/internal/admin/experiments", EXPERIMENTS);
    }

    @Override
    public ExperimentSettingsDto adminExperiment(UUID id) {
        return client.get("/api/internal/admin/experiments/{id}/settings", ExperimentSettingsDto.class, id);
    }

    @Override
    public List<FlagKeyDto> adminFlagKeys() {
        return client.get("/api/internal/admin/flags", FLAG_KEYS);
    }

    @Override
    public int adminExperimentAction(UUID id, String action) {
        // one literal per route, so catapult-web's calls can be checked against catapult-api's routes
        ApiClient.Result<Void> result = switch (action) {
            case "activate" -> client.call(HttpMethod.POST, "/api/internal/admin/experiments/{id}/activate", null, Void.class, id);
            case "pause" -> client.call(HttpMethod.POST, "/api/internal/admin/experiments/{id}/pause", null, Void.class, id);
            case "end" -> client.call(HttpMethod.POST, "/api/internal/admin/experiments/{id}/end", null, Void.class, id);
            case "assign" -> client.call(HttpMethod.POST, "/api/internal/admin/experiments/{id}/assign", null, Void.class, id);
            case "reassign" -> client.call(HttpMethod.POST, "/api/internal/admin/experiments/{id}/reassign", null, Void.class, id);
            // no such route: what catapult-api answers it
            default -> new ApiClient.Result<>(HttpStatus.NOT_FOUND.value(), null);
        };
        return result.status();
    }

    @Override
    public int adminExperimentWeights(UUID id, Map<UUID, Integer> weights) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/experiments/{id}/variants/weights", weights, Void.class, id).status();
    }

    @Override
    public ExperimentRebalanceDto adminExperimentWeightsPreview(UUID id, Map<UUID, Integer> weights) {
        ApiClient.Result<ExperimentRebalanceDto> result = client.call(HttpMethod.POST,
                "/api/internal/admin/experiments/{id}/variants/weights/preview", weights, ExperimentRebalanceDto.class, id);
        return result.ok() ? result.body() : null;
    }

    @Override
    public int adminAddExperimentRule(UUID id, ExperimentRuleRequest rule) {
        return client.call(HttpMethod.POST, "/api/internal/admin/experiments/{id}/rules", rule, Void.class, id).status();
    }

    @Override
    public int adminDeleteExperimentRule(UUID id, UUID ruleId) {
        return client.delete("/api/internal/admin/experiments/{id}/rules/{ruleId}", id, ruleId);
    }

    @Override
    public int adminAddExperimentOverride(UUID id, ExperimentOverrideRequest override) {
        return client.call(HttpMethod.POST, "/api/internal/admin/experiments/{id}/overrides", override, Void.class, id).status();
    }

    @Override
    public int adminDeleteExperimentOverride(UUID id, UUID overrideId) {
        return client.delete("/api/internal/admin/experiments/{id}/overrides/{overrideId}", id, overrideId);
    }

    @Override
    public ExperimentAssignmentsDto adminExperimentAssignments(UUID id, UUID variantId, String q, int page) {
        return client.get("/api/internal/admin/experiments/{id}/assignments?variant={variant}&q={q}&page={page}",
                ExperimentAssignmentsDto.class, id, variantId == null ? "" : variantId, q == null ? "" : q, page);
    }

    @Override
    public int adminMoveExperimentAssignment(UUID id, UUID userId, UUID variantId) {
        return client.call(HttpMethod.PUT, "/api/internal/admin/experiments/{id}/assignments/{memberId}/variant",
                new MoveAssignmentRequest(variantId), Void.class, id, userId).status();
    }

    @Override
    public WhitelistPageDto adminWhitelist() {
        return client.get("/api/internal/admin/whitelist", WhitelistPageDto.class);
    }

    @Override
    public int adminToggleWhitelist() {
        return client.call(HttpMethod.POST, "/api/internal/admin/whitelist/toggle", null, Void.class).status();
    }

    @Override
    public int adminAddWhitelist(String twitchId) {
        return client.call(HttpMethod.POST, "/api/internal/admin/whitelist/entries", Map.of("twitchId", twitchId), Void.class).status();
    }

    @Override
    public int adminDeleteWhitelist(String twitchId) {
        return client.delete("/api/internal/admin/whitelist/entries/{id}", twitchId);
    }

    @Override
    public int adminToggleInvites() {
        return client.call(HttpMethod.POST, "/api/internal/admin/whitelist/invite/toggle", null, Void.class).status();
    }

    @Override
    public int adminSaveInviteSettings(Integer globalMaxMembers, Integer defaultMaxUses, boolean defaultCanReinvite) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("globalMaxMembers", globalMaxMembers);
        body.put("defaultMaxUses", defaultMaxUses);
        body.put("defaultCanReinvite", defaultCanReinvite);
        return client.call(HttpMethod.PUT, "/api/internal/admin/whitelist/invite/settings", body, Void.class).status();
    }

    @Override
    public int adminSaveInviteQuota(UUID inviteId, Integer maxUses, Boolean canReinvite) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("maxUses", maxUses);
        body.put("canReinvite", canReinvite);
        return client.call(HttpMethod.PUT, "/api/internal/admin/whitelist/invite/{inviteId}/quota", body, Void.class, inviteId).status();
    }

    @Override
    public AdminMemberSummaryDto adminMember(UUID id) {
        return client.get("/api/internal/admin/members/{id}", AdminMemberSummaryDto.class, id);
    }

    @Override
    public MemberTargetingDto adminMemberTargeting(UUID id) {
        return client.get("/api/internal/admin/members/{id}/targeting", MemberTargetingDto.class, id);
    }

    @Override
    public int adminSetMemberFlag(UUID id, String key, String value) {
        // a body map (not Map.of): the value is null for a flag set without one
        Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("key", key);
        body.put("value", value);
        return client.call(HttpMethod.POST, "/api/internal/admin/members/{id}/flags", body, Void.class, id).status();
    }

    @Override
    public int adminDeleteMemberFlag(UUID id, String key) {
        // the key is a URI variable, so a "/", "%" or space in it is encoded rather than read as part of the path
        return client.delete("/api/internal/admin/members/{id}/flags/{key}", id, key);
    }

    @Override
    public int adminAddMemberToGroup(UUID id, String groupKey) {
        return client.call(HttpMethod.POST, "/api/internal/admin/members/{id}/groups", Map.of("groupKey", groupKey), Void.class, id).status();
    }

    @Override
    public int adminRemoveMemberFromGroup(UUID id, UUID groupId) {
        return client.delete("/api/internal/admin/members/{id}/groups/{groupId}", id, groupId);
    }

    @Override
    public int adminBroadcast(BroadcastRequestDto body) {
        return client.call(HttpMethod.POST, "/api/internal/admin/broadcasts", body, Void.class).status();
    }

    @Override
    public UserInfoResponse accountInfo() {
        return client.get("/api/internal/auth/validate", UserInfoResponse.class);
    }

    @Override
    public int validateSession() {
        return client.call(HttpMethod.GET, "/api/internal/auth/validate", null, Void.class).status();
    }

    @Override
    public int accountDeletionDelayDays() {
        AppConfigResponse config = client.get("/api/internal/config/app", AppConfigResponse.class);
        return config == null || config.deletionDelayDays() <= 0 ? DEFAULT_DELETION_DELAY_DAYS : config.deletionDelayDays();
    }

    @Override
    public int deleteAccount(String username, String confirmUsername) {
        return onChannel(username, id -> client.call(HttpMethod.POST, "/api/internal/users/{userId}/deletion",
                new DeleteAccountRequest(confirmUsername), Void.class, id).status(), status -> status);
    }

    @Override
    public int cancelAccountDeletion(String username) {
        return onChannel(username, id -> client.call(HttpMethod.DELETE, "/api/internal/users/{userId}/deletion", null,
                Void.class, id).status(), status -> status);
    }

    @Override
    public InvitePageData invitePage() {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/invite", InvitePageData.class, me);
    }

    @Override
    public ApiClient.Result<InvitePageData> regenerateInvite() {
        UUID me = me();
        if (me == null) return new ApiClient.Result<>(HttpStatus.UNAUTHORIZED.value(), null);
        return client.call(HttpMethod.POST, "/api/internal/users/{userId}/invite/regenerate", null, InvitePageData.class, me);
    }

    @Override
    public ApiKeysPageDto adminApiKeys(ApiKeyProvider provider) {
        return switch (provider) {
            case STEAM -> {
                SteamKeysPageData page = client.get("/api/internal/admin/steam-keys", SteamKeysPageData.class);
                yield page == null ? null : ApiKeysPageDto.of(page);
            }
            case DTDD -> {
                DtddKeysPageData page = client.get("/api/internal/admin/dtdd-keys", DtddKeysPageData.class);
                yield page == null ? null : ApiKeysPageDto.of(page);
            }
        };
    }

    @Override
    public int adminAddApiKey(ApiKeyProvider provider, String apiKey) {
        Map<String, String> body = Map.of("apiKey", apiKey);
        ApiClient.Result<Void> result = switch (provider) {
            case STEAM -> client.call(HttpMethod.POST, "/api/internal/admin/steam-keys", body, Void.class);
            case DTDD -> client.call(HttpMethod.POST, "/api/internal/admin/dtdd-keys", body, Void.class);
        };
        return result.status();
    }

    @Override
    public int adminDeleteApiKey(ApiKeyProvider provider, String keyId) {
        ApiClient.Result<Void> result = switch (provider) {
            case STEAM -> client.call(HttpMethod.DELETE, "/api/internal/admin/steam-keys/{keyId}", null, Void.class, keyId);
            case DTDD -> client.call(HttpMethod.DELETE, "/api/internal/admin/dtdd-keys/{keyId}", null, Void.class, keyId);
        };
        return result.status();
    }

    @Override
    public int adminRefreshApiKeys(ApiKeyProvider provider) {
        ApiClient.Result<Void> result = switch (provider) {
            case STEAM -> client.call(HttpMethod.POST, "/api/internal/admin/steam-keys/refresh", null, Void.class);
            case DTDD -> client.call(HttpMethod.POST, "/api/internal/admin/dtdd-keys/refresh", null, Void.class);
        };
        return result.status();
    }

    @Override
    public List<AccountDto> adminMinecraftAccounts() {
        // a 404 (Minecraft disabled in catapult-api) is null, like any failed read
        AccountDto[] accounts = client.get("/api/internal/admin/minecraft-accounts", AccountDto[].class);
        return accounts == null ? null : List.of(accounts);
    }

    @Override
    public int adminPatchMinecraftAccount(UUID id, Map<String, Object> patch) {
        return client.call(HttpMethod.PATCH, "/api/internal/admin/minecraft-accounts/{id}", patch, Void.class, id).status();
    }

    @Override
    public int adminDeleteMinecraftAccount(UUID id) {
        return client.call(HttpMethod.DELETE, "/api/internal/admin/minecraft-accounts/{id}", null, Void.class, id).status();
    }

    @Override
    public MinecraftDeviceCodeStart adminMinecraftDeviceCode() {
        // read as a map: the code on 200 (snake_case, Microsoft's own fields); a refusal is read as its problem
        @SuppressWarnings("rawtypes")
        ApiClient.Result<Map> result = client.call(HttpMethod.POST, "/api/internal/admin/minecraft-accounts/device-code", null, Map.class);
        Map<?, ?> body = result.body() == null ? Map.of() : result.body();
        if (result.status() == 200) {
            if (!(body.get("device_code") instanceof String deviceCode) || deviceCode.isBlank()) {
                return new MinecraftDeviceCodeStart(502, null, null);
            }
            return new MinecraftDeviceCodeStart(200, new MinecraftDeviceCodeDto(deviceCode, text(body.get("user_code")),
                    text(body.get("verification_uri")), body.get("interval") instanceof Number n ? n.intValue() : 0,
                    body.get("expires_in") instanceof Number n ? n.intValue() : 0), null);
        }
        return new MinecraftDeviceCodeStart(result.ok() ? 502 : result.status(), null, result.error());
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    @Override
    public ApiClient.Result<Map<String, Object>> adminCreateMinecraftAccount(String deviceCode, String label) {
        Map<String, String> body = label == null ? Map.of("deviceCode", deviceCode) : Map.of("deviceCode", deviceCode, "label", label);
        return mapResult(client.call(HttpMethod.POST, "/api/internal/admin/minecraft-accounts", body, Map.class));
    }

    @Override
    public ApiClient.Result<Map<String, Object>> adminReauthMinecraftAccount(UUID id, String deviceCode) {
        return mapResult(client.call(HttpMethod.POST, "/api/internal/admin/minecraft-accounts/{id}/reauth", Map.of("deviceCode", deviceCode), Map.class, id));
    }

    @SuppressWarnings("unchecked")
    private static ApiClient.Result<Map<String, Object>> mapResult(@SuppressWarnings("rawtypes") ApiClient.Result<Map> result) {
        return new ApiClient.Result<>(result.status(), (Map<String, Object>) result.body());
    }

    @Override
    public List<TwDefinitionDto> adminTws() {
        return client.get("/api/internal/admin/tws", TWS);
    }

    @Override
    public TwDefinitionDto adminTw(String id) {
        return client.get("/api/internal/admin/tws/{id}", TwDefinitionDto.class, id);
    }

    @Override
    public int adminCreateTw(String id, String label, int sortOrder) {
        return client.call(HttpMethod.POST, "/api/internal/admin/tws", new AdminTwCreateBody(id, label, null, sortOrder), Void.class).status();
    }

    @Override
    public int adminRebuildTws(TwRebuild mode) {
        ApiClient.Result<Void> result = switch (mode) {
            case NON_OVERRIDDEN -> client.call(HttpMethod.POST, "/api/internal/admin/tws/rebuild", null, Void.class);
            case UNTOUCHED -> client.call(HttpMethod.POST, "/api/internal/admin/tws/rebuild/force", null, Void.class);
            case FORCE_PINNED -> client.call(HttpMethod.POST, "/api/internal/admin/tws/rebuild/force-pinned", null, Void.class);
        };
        return result.status();
    }

    @Override
    public List<String> adminTwKeywords(String id) {
        return client.get("/api/internal/admin/tws/{id}/steam-keywords", new ParameterizedTypeReference<List<String>>() {}, id);
    }

    @Override
    public int adminAddTwKeyword(String id, String keyword) {
        return client.call(HttpMethod.POST, "/api/internal/admin/tws/{id}/steam-keywords", new SteamKeywordBody(keyword), Void.class, id).status();
    }

    @Override
    public int adminRemoveTwKeyword(String id, String keyword) {
        // free text: a URI variable, encoded as one segment
        return client.call(HttpMethod.DELETE, "/api/internal/admin/tws/{id}/steam-keywords/{keyword}", null, Void.class, id, keyword).status();
    }

    @Override
    public TwKeywordTestDto adminTestTwKeyword(String id, String appId, String draftKeyword) {
        var result = client.call(HttpMethod.POST, "/api/internal/admin/tws/{id}/steam-keywords/test",
                new SteamSignalTestBody(appId, draftKeyword), TwKeywordTestDto.class, id);
        return result.ok() ? result.body() : null;
    }

    @Override
    public fr.enimaloc.catapult.common.dto.HelpContent help(String cardId, java.util.Locale locale) {
        return client.getLocalized("/api/internal/help-cards/{cardId}", fr.enimaloc.catapult.common.dto.HelpContent.class, locale, cardId);
    }

    @Override
    public String steamConnectUrl() {
        UUID me = me();
        if (me == null) return null;
        SteamStartResponse start = client.get("/api/internal/users/{userId}/links/steam/start", SteamStartResponse.class, me);
        return start == null ? null : start.redirectUrl();
    }

    @Override
    public int steamDisconnect() {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.delete("/api/internal/users/{userId}/links/steam", me);
    }

    @Override
    public List<ChangelogSection> changelog() {
        ApiClient.Result<ChangelogSection[]> result = client.getAnonymous("/api/internal/changelog", ChangelogSection[].class);
        return result.ok() && result.body() != null ? List.of(result.body()) : null;
    }

    @Override
    public DegradedStatusDto degradedStatus() {
        ApiClient.Result<DegradedStatusDto> result = client.getAnonymous("/api/internal/status/degraded", DegradedStatusDto.class);
        return result.ok() ? result.body() : null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public boolean apiAvailable() {
        ApiClient.Result<Map> health = client.getAnonymous("/api/internal/health", Map.class);
        return health.ok() && health.body() != null && "UP".equals(health.body().get("status"));
    }

    @Override
    public CustomBindingsStateDto customBindings(String username) {
        return readChannel(username, id -> client.getResult("/api/internal/users/{userId}/custom-bindings",
                CustomBindingsStateDto.class, id));
    }

    @Override
    public CustomBindingSave<CustomBindingDto> createCustomBinding(String username, CustomBindingRequest request) {
        return save(onChannel(username, id -> client.call(HttpMethod.POST, "/api/internal/users/{userId}/custom-bindings",
                request, JsonNode.class, id), status -> new ApiClient.Result<>(status, null)), CustomBindingDto.class);
    }

    @Override
    public CustomBindingSave<CustomBindingSaveResult> updateCustomBinding(String username, UUID id, CustomBindingRequest request) {
        return save(onChannel(username, userId -> client.call(HttpMethod.PUT,
                "/api/internal/users/{userId}/custom-bindings/{customBindingId}", request, JsonNode.class, userId, id),
                status -> new ApiClient.Result<>(status, null)), CustomBindingSaveResult.class);
    }

    /** The saved binding when catapult-api took it; else the code of its problem. */
    private static <T> CustomBindingSave<T> save(ApiClient.Result<JsonNode> result, Class<T> type) {
        JsonNode body = result.body();
        if (result.ok()) {
            return body == null ? new CustomBindingSave<>(502, null, null)
                    : new CustomBindingSave<>(result.status(), SAVE_JSON.treeToValue(body, type), null);
        }
        return new CustomBindingSave<>(result.status(), null, result.error() == null ? null : result.error().code());
    }

    @Override
    public int deleteCustomBinding(String username, UUID id) {
        return onChannel(username, userId -> client.delete("/api/internal/users/{userId}/custom-bindings/{customBindingId}",
                userId, id), status -> status);
    }

    @Override
    public ActivationOutcome activateCustomBinding(String username, UUID id) {
        // Its 409 problem tells a missing Twitch link from a paused bot.
        ApiClient.Result<Void> result = onChannel(username, userId -> client.call(HttpMethod.POST,
                "/api/internal/users/{userId}/custom-bindings/{customBindingId}/activate", null, Void.class, userId, id),
                status -> new ApiClient.Result<>(status, null));
        return ActivationOutcome.of(result.status(), result.error() == null ? null : result.error().code());
    }

    @Override
    public int deactivateCustomBinding(String username) {
        return onChannel(username, id -> client.postVoid("/api/internal/users/{userId}/custom-bindings/deactivate", null, id),
                status -> status);
    }

    @Override
    public ApiTokenStatusDto apiTokenStatus() {
        UUID me = me();
        if (me == null) return null;
        return client.get("/api/internal/users/{userId}/api-token", ApiTokenStatusDto.class, me);
    }

    @Override
    public ApiClient.Result<ApiTokenCreatedDto> regenerateApiToken() {
        UUID me = me();
        if (me == null) return new ApiClient.Result<>(HttpStatus.UNAUTHORIZED.value(), null);
        // The answer carries the new token, shown once: ApiClient never logs a body, and nothing here keeps it.
        ApiClient.Result<ApiTokenCreatedDto> result = client.call(HttpMethod.POST, "/api/internal/users/{userId}/api-token", null,
                ApiTokenCreatedDto.class, me);
        return result.ok() && result.body() != null ? result : new ApiClient.Result<>(result.ok() ? 502 : result.status(), null);
    }

    @Override
    public int revokeApiToken() {
        UUID me = me();
        if (me == null) return HttpStatus.UNAUTHORIZED.value();
        return client.delete("/api/internal/users/{userId}/api-token", me);
    }

    /**
     * {@code call} on the channel named {@code username}, given its account id (the {@code userId} of a channel's
     * routes): {@code refused} with the lookup's status, without calling, when catapult-api does not tell it (401 for
     * an unknown caller, 404 for an unknown channel, as the channel route would answer). A call that answers 404 drops
     * the id kept for {@code username}, which may have been deleted then re-created.
     */
    private <T> T onChannel(String username, Function<UUID, T> call, IntFunction<T> refused) {
        ChannelIds.Lookup lookup = channelIds.resolve(username);
        if (!lookup.found()) return refused.apply(lookup.status());
        T answer = call.apply(lookup.id());
        if (statusOf(answer) == HttpStatus.NOT_FOUND.value()) channelIds.forget(username);
        return answer;
    }

    /** A channel's read ({@link #onChannel}): its body, null when refused or empty. */
    private <T> T readChannel(String username, Function<UUID, ApiClient.Result<T>> read) {
        ApiClient.Result<T> result = onChannel(username, read, status -> new ApiClient.Result<>(status, null));
        return result.ok() ? result.body() : null;
    }

    /** The status a channel call answered: its {@code int}, or its {@link ApiClient.Result}'s; 0 for anything else. */
    private static int statusOf(Object answer) {
        if (answer instanceof Integer status) return status;
        if (answer instanceof ApiClient.Result<?> result) return result.status();
        return 0;
    }

    /**
     * The calling session's account id, the {@code userId} of a member's own routes (an impersonating admin's session
     * holds the target's JWT, so it is the target's); null without a session.
     */
    private static UUID me() {
        return ApiClient.currentSubject();
    }
}
