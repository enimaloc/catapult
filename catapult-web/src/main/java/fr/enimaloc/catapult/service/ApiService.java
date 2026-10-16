package fr.enimaloc.catapult.service;

import java.util.List;
import java.util.Map;
import fr.enimaloc.catapult.common.dto.*;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDetailDto;
import fr.enimaloc.catapult.common.dto.admin.CacheEntryDto;
import fr.enimaloc.catapult.common.dto.admin.CacheSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.RepoSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.IanaRegistryDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDetailDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileDto;
import fr.enimaloc.catapult.common.dto.admin.WellKnownFileRequest;
import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.SearchResponse;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEventActionDto;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatValidationError;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;

public interface ApiService {
    TokenResponse exchangeCode(String code);
    ChannelListResponse channelList();
    ChannelPageData channelPage(String username, int page, String status, String source);
    void toggleBot(String username);
    void recheckGame(String username);
    /** The game Catapult currently detects on the channel; null when none. */
    GameDto currentGame(String username);
    void cclToggle(String username, String bindingId, boolean enabled);
    void ignoredToggle(String username, String bindingId, boolean ignored);
    void deleteBinding(String username, String bindingId);
    Object searchGames(String username, String q);
    void updateBinding(String username, String bindingId, String twitchGameId, String twitchGameName, java.util.Set<String> ccls);
    void saveTws(String bindingId, java.util.Set<String> tws);
    void resetTws(String bindingId);
    void toggleTwEnabled(String bindingId, boolean enabled);
    void saveSteamToken(String username, String token, boolean shared);
    void steamTokenSharing(String username, boolean shared);
    void deleteSteamToken(String username);
    void refreshSteamProfileCache(String username);
    LinkStateResponse minecraftStatus(String username);
    void minecraftEnroll(String username, String name);
    void minecraftSync(String username);
    void minecraftDisconnect(String username);
    /** The logged-in user's OBS connection; null when logged out or OBS isn't enabled. */
    TwitchatWidgetConfig obsConnection();
    void saveObsSettings(String username, boolean enabled, String host, Integer port, String password);
    /** "auto" or one of TwitchatData.SUPPORTED_BRANCHES. */
    void saveTwitchatBranch(String username, String branch);
    void saveCclSettings(String username, boolean enabled, java.util.Set<String> blockedCcls);
    void saveTwSettings(String username, boolean enabled, java.util.Set<String> blockedTws);
    UserSettingsDto channelSettings(String username);
    void saveNoGameSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls,
                             boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd);
    void saveIncompleteFallbackSettings(String username, String twitchGameId, String twitchGameName, java.util.Set<String> ccls);
    DtddMappingStatusDto dtddMappingStatus(String username);
    SearchResponse dtddSearch(String q);
    void dtddValidate(String igdbId);
    void dtddPropose(String igdbId, Long dtddId, String reason);
    /** The logged-in user's account id (the subject of their JWT); null when logged out. */
    java.util.UUID currentUserId();
    /** Whether the logged-in user is a Catapult admin; false when logged out. */
    boolean isAdmin();
    /** Every event's Twitchat payloads, by event name. */
    java.util.Map<String, java.util.List<TwitchatEventActionDto>> twitchatEvents(String username);
    /** Replaces an event's payloads; the validation errors, empty when saved. */
    java.util.List<TwitchatValidationError> saveTwitchatEvent(String username, String eventType, java.util.List<TwitchatEventActionDto> payloads);
    /** Sends payloads as edited, saved or not; the validation errors, empty when sent. */
    java.util.List<TwitchatValidationError> testTwitchatEvent(String username, String eventType, java.util.List<TwitchatEventActionDto> payloads);
    /** Runs the Catapult action behind a Twitchat button's token; false when used or expired. */
    boolean executeTwitchatAction(String token);

    // --- admin: members (catapult-api refuses them to anyone but an admin) ---
    /** Every member, and whether each one is live. */
    AdminMembersPage adminMembers();
    void adminToggleMemberBot(java.util.UUID memberId);
    void adminUnlinkMemberSteam(java.util.UUID memberId);
    /** Leaves the account inactive, without Twitch identity nor bot. */
    void adminUnlinkMemberTwitch(java.util.UUID memberId);
    /** Makes the member the account posting chat command replies, in place of the previous one. */
    void adminPromoteMemberToSystem(java.util.UUID memberId);
    void adminDeleteMember(java.util.UUID memberId);
    /** Copies the chosen parts of a member's data onto another member. */
    void adminMigrateMember(java.util.UUID memberId, java.util.UUID targetId,
                            boolean settings, boolean getters, boolean bindings);

    // --- admin: CCL, caches and data tabs (catapult-api refuses them to anyone but an admin) ---

    /** Twitch's CCLs and the IGDB descriptors they map to; null when catapult-api fails. */
    AdminCclPage adminCcl();
    /** Reloads the CCLs from Twitch: catapult-api's status (204), 502 when unreachable. */
    int adminRefreshCcl();
    /** Maps a CCL to IGDB descriptors: catapult-api's status (204; the mock's 404 for an unknown CCL), 502 when unreachable. */
    int adminSaveCclMappings(String cclId, java.util.Set<Long> igdbDescriptorIds);

    /** catapult-api's in-memory caches; null when it fails. */
    List<CacheSummaryDto> adminCaches();
    /** One page of a cache's entries matching {@code q} (all when blank); null when it fails or the cache is unknown. */
    AdminPage<CacheEntryDto> adminCacheEntries(String name, String q, int page);
    /** A cache entry and the record behind it; null when catapult-api fails. */
    CacheEntryDetailDto adminCacheEntry(String name, String key);
    /** Evicts a cache entry: catapult-api's status (204; 400 read-only cache; 404), 502 when unreachable. */
    int adminDeleteCacheEntry(String name, String key);

    /** The repositories the data tab can browse; null when catapult-api fails. */
    List<RepoSummaryDto> adminDataRepos();
    /** One page of a repository's rows matching {@code q}; null when it fails or the repository is unknown. */
    AdminPage<Map<String, Object>> adminDataRows(String repo, String q, int page);
    /** One row, its collection relations included; null when it fails or there is no such row. */
    Map<String, Object> adminDataRow(String repo, String id);
    /** catapult-api's status (201; 400 invalid value), 502 when unreachable. */
    int adminCreateDataRow(String repo, Map<String, String> fields);
    /** catapult-api's status (200; 400 invalid value; 404), 502 when unreachable. */
    int adminUpdateDataRow(String repo, String id, Map<String, String> fields);
    /** catapult-api's status (204; 404; 409 still referenced), 502 when unreachable. */
    int adminDeleteDataRow(String repo, String id);

    // --- well-known files: served to anyone, edited from the admin page ---

    /** The file configured at {@code path}: catapult-api's answer (200 / 404), 502 when unreachable. */
    WellKnownContent wellKnown(WellKnownLocation location, String path);
    /** The configured well-known files; null when catapult-api fails. */
    List<WellKnownFileDto> adminWellKnownFiles();
    /** One configured file with its content; null when it fails or there is none. */
    WellKnownFileDetailDto adminWellKnownFile(long id);
    /** IANA's registry (live or catapult-api's bundled copy); null when catapult-api fails. */
    IanaRegistryDto adminIanaRegistry();
    /** catapult-api's status (201; 400 invalid; 409 path taken), 502 when unreachable. */
    int adminCreateWellKnownFile(WellKnownFileRequest request);
    /** catapult-api's status (200; 400; 404; 409), 502 when unreachable. */
    int adminUpdateWellKnownFile(long id, WellKnownFileRequest request);
    /** catapult-api's status (204; 404), 502 when unreachable. */
    int adminDeleteWellKnownFile(long id);
}
