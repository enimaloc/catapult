package fr.enimaloc.catapult.service.real;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.SaveMappingsRequest;
import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.common.dto.channel.CclSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.CclToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.IgnoredToggleRequest;
import fr.enimaloc.catapult.common.dto.channel.IncompleteFallbackRequest;
import fr.enimaloc.catapult.common.dto.channel.NoGameSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.ObsSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.TwitchatBranchRequest;
import fr.enimaloc.catapult.common.dto.channel.ProposeRequest;
import fr.enimaloc.catapult.common.dto.channel.SaveBody;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.StatusData;
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
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
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
    private static final ParameterizedTypeReference<Map<String, List<TwitchatEventActionDto>>> EVENTS = new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<AdminPage<CacheEntryDto>> CACHE_ENTRIES = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<AdminPage<Map<String, Object>>> DATA_ROWS = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> DATA_ROW = new ParameterizedTypeReference<>() {};

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
    public void toggleBot(String username) {
        client.postVoid("/api/channels/{username}/settings/bot", null, username);
    }

    @Override
    public void recheckGame(String username) {
        client.postVoid("/api/channels/{username}/game/recheck", null, username);
    }

    @Override
    public GameDto currentGame(String username) {
        StatusData status = client.get("/api/channels/{username}/status", StatusData.class, username);
        return status == null ? null : status.currentGame();
    }

    @Override
    public void cclToggle(String username, String bindingId, boolean enabled) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}/ccl-toggle",
                new CclToggleRequest(enabled), username, bindingId);
    }

    @Override
    public void ignoredToggle(String username, String bindingId, boolean ignored) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}/ignored-toggle",
                new IgnoredToggleRequest(ignored), username, bindingId);
    }

    @Override
    public void deleteBinding(String username, String bindingId) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}/delete", null, username, bindingId);
    }

    @Override
    public Object searchGames(String username, String q) {
        return client.get("/api/channels/{username}/games/search?q={q}", Object.class, username, q);
    }

    @Override
    public void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, Set<String> ccls) {
        client.postVoid("/api/channels/{username}/bindings/{bindingId}",
                new UpdateBindingRequest(twitchGameId, twitchGameName, ccls), username, bindingId);
    }

    @Override
    public void saveTws(String bindingId, Set<String> tws) {
        client.postVoid("/api/channel/bindings/{bindingId}/tws", new SaveBody(tws), bindingId);
    }

    @Override
    public void resetTws(String bindingId) {
        client.postVoid("/api/channel/bindings/{bindingId}/tws/reset", null, bindingId);
    }

    @Override
    public void toggleTwEnabled(String bindingId, boolean enabled) {
        client.postVoid("/api/channel/bindings/{bindingId}/tw-enabled", new TwEnabledBody(enabled), bindingId);
    }

    @Override
    public void saveSteamToken(String username, String token, boolean shared) {
        client.postVoid("/api/channels/{username}/settings/steam-personal-token",
                new SteamTokenRequest(token, shared), username);
    }

    @Override
    public void steamTokenSharing(String username, boolean shared) {
        client.postVoid("/api/channels/{username}/settings/steam-personal-token/sharing",
                new SteamTokenSharingRequest(shared), username);
    }

    @Override
    public void deleteSteamToken(String username) {
        client.postVoid("/api/channels/{username}/settings/steam-personal-token/delete", null, username);
    }

    @Override
    public void refreshSteamProfileCache(String username) {
        client.postVoid("/api/channels/{username}/steam/refresh-profile-cache", null, username);
    }

    @Override
    public LinkStateResponse minecraftStatus(String username) {
        return client.get("/api/connect/minecraft", LinkStateResponse.class);
    }

    @Override
    public void minecraftEnroll(String username, String name) {
        client.postVoid("/api/connect/minecraft", Map.of("name", name));
    }

    @Override
    public void minecraftSync(String username) {
        client.postVoid("/api/connect/minecraft/sync", null);
    }

    @Override
    public void minecraftDisconnect(String username) {
        client.delete("/api/connect/minecraft");
    }

    @Override
    public TwitchatWidgetConfig obsConnection() {
        return client.get("/api/me/obs", TwitchatWidgetConfig.class);
    }

    @Override
    public void saveObsSettings(String username, boolean enabled, String host, Integer port, String password) {
        client.postVoid("/api/channels/{username}/settings/obs",
                new ObsSettingsRequest(enabled, host, port, password), username);
    }

    @Override
    public void saveTwitchatBranch(String username, String branch) {
        client.postVoid("/api/channels/{username}/settings/twitchat/branch", new TwitchatBranchRequest(branch), username);
    }

    @Override
    public void saveCclSettings(String username, boolean enabled, Set<String> blockedCcls) {
        client.postVoid("/api/channels/{username}/settings/ccl",
                new CclSettingsRequest(enabled, blockedCcls), username);
    }

    @Override
    public void saveTwSettings(String username, boolean enabled, Set<String> blockedTws) {
        client.postVoid("/api/channels/{username}/settings/tws",
                new TwSettingsRequest(enabled, blockedTws), username);
    }

    @Override
    public UserSettingsDto channelSettings(String username) {
        return client.get("/api/channels/{username}/settings", UserSettingsDto.class, username);
    }

    @Override
    public void saveNoGameSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        client.postVoid("/api/channels/{username}/settings/no-game",
                new NoGameSettingsRequest(twitchGameId, twitchGameName, ccls, applyOnStreamStart, applyOnNoGame, applyOnStreamEnd),
                username);
    }

    @Override
    public void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, Set<String> ccls) {
        client.postVoid("/api/channels/{username}/settings/incomplete-fallback",
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
    public void dtddValidate(String igdbId) {
        client.postVoid("/api/channel/dtdd-mapping/validate", new ValidateRequest(igdbId));
    }

    @Override
    public void dtddPropose(String igdbId, Long dtddId, String reason) {
        client.postVoid("/api/channel/dtdd-mapping/propose", new ProposeRequest(igdbId, dtddId, reason));
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
    public AdminMembersPage adminMembers() {
        return client.get("/api/admin/members", AdminMembersPage.class);
    }

    @Override
    public void adminToggleMemberBot(UUID memberId) {
        client.postVoid("/api/admin/members/{id}/bot/toggle", null, memberId);
    }

    @Override
    public void adminUnlinkMemberSteam(UUID memberId) {
        client.postVoid("/api/admin/members/{id}/steam/unlink", null, memberId);
    }

    @Override
    public void adminUnlinkMemberTwitch(UUID memberId) {
        client.postVoid("/api/admin/members/{id}/twitch/unlink", null, memberId);
    }

    @Override
    public void adminPromoteMemberToSystem(UUID memberId) {
        client.postVoid("/api/admin/members/{id}/promote-to-system", null, memberId);
    }

    @Override
    public void adminDeleteMember(UUID memberId) {
        client.postVoid("/api/admin/members/{id}/delete", null, memberId);
    }

    @Override
    public void adminMigrateMember(UUID memberId, UUID targetId, boolean settings, boolean getters, boolean bindings) {
        client.postVoid("/api/admin/members/{id}/migrate", new MigrateRequest(targetId, settings, getters, bindings), memberId);
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
    public int adminDeleteDataRow(String repo, String id) {
        return client.call(HttpMethod.DELETE, "/api/admin/data/{repo}/{id}", null, Void.class, repo, id).status();
    }

    /** No error when it went through; a 4xx/5xx without a list becomes one error naming the status. */
    private static List<TwitchatValidationError> errors(ApiClient.Result<TwitchatValidationError[]> result) {
        if (result.ok()) return List.of();
        if (result.body() != null) return List.of(result.body());
        return List.of(new TwitchatValidationError(-1, "", "HTTP " + result.status()));
    }
}
