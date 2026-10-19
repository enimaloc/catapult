package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.common.dto.InvitePageData;
import fr.enimaloc.catapult.common.dto.UserInfoResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import fr.enimaloc.catapult.service.http.ApiClient;
import fr.enimaloc.catapult.common.dto.*;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.AdminCclPage;
import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.GroupSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto;
import fr.enimaloc.catapult.common.dto.admin.WhitelistPageDto;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
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
    /** SchemaStore's schema for {@code path}; null when none or catapult-api fails. */
    String adminWellKnownSchema(String path);
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

    // --- notifications and broadcasts ---

    /** The bell's content: the newest notifications and the unread count; null when catapult-api fails. */
    NotificationSnapshotDto notificationSnapshot();
    /** One page (20, newest first) of the logged-in user's notifications; null when catapult-api fails. */
    AdminPage<NotificationDto> notifications(int page);
    /** catapult-api's status (204; 404), 502 when unreachable. */
    int markNotificationRead(UUID id);
    /** catapult-api's status (204), 502 when unreachable. */
    int markAllNotificationsRead();
    /** One page (20, newest first) of every notification sent; null when catapult-api fails. */
    AdminPage<AdminNotificationDto> adminNotifications(int page);
    /** catapult-api's status (201; 400 invalid), 502 when unreachable. */
    int adminCreateNotification(AdminNotificationCreateBody body);
    /** catapult-api's status (204; 404), 502 when unreachable. */
    int adminDeleteNotification(UUID id);
    /**
     * The sanitized HTML catapult-api renders for a Markdown body, with its status (200; 400 empty
     * body; 404 an older catapult-api without the route), 502 when unreachable or answered without HTML.
     */
    ApiClient.Result<String> adminPreviewNotification(String body);
    /**
     * A token logged in as the member {@code username}, which catapult-api mints for an admin, with its
     * status (200; 404 unknown; 403 not active, the admin themself or a system account); 502 when
     * unreachable or answered without a token.
     */
    ApiClient.Result<String> adminImpersonate(String username);
    /** The DTDD mapping proposals with {@code status} (PENDING, APPROVED, REJECTED), oldest first; null when catapult-api fails. */
    List<DtddProposalDto> adminDtddProposals(String status);
    /** catapult-api's status (204; 404 unknown; 409 already resolved), 502 when unreachable. */
    int adminApproveDtddProposal(UUID id);
    /** As {@link #adminApproveDtddProposal}; the reason is sent only when not blank. */
    int adminRejectDtddProposal(UUID id, String reason);
    /** The member groups, with their member counts; null when catapult-api fails. */
    List<GroupSummaryDto> adminGroups();
    /** catapult-api's status (201; 400 blank key; 409 key already used), 502 when unreachable. */
    int adminCreateGroup(String key, String name, String description);
    /** catapult-api's status (204; 404 unknown group), 502 when unreachable. */
    int adminRenameGroup(UUID id, String name, String description);
    /** catapult-api's status (204; 404 unknown group), 502 when unreachable. */
    int adminDeleteGroup(UUID id);
    /** catapult-api's status (204; 404 unknown group or Twitch username), 502 when unreachable. */
    int adminAddGroupMember(UUID id, String twitchUsername);

    /** The whitelist, its entries, invitation settings and invites; null when catapult-api fails. */
    WhitelistPageDto adminWhitelist();
    /** Flips the whitelist; catapult-api's status (204), 502 when unreachable. */
    int adminToggleWhitelist();
    /** Adds a Twitch id (already listed changes nothing); catapult-api's status (204), 502 when unreachable. */
    int adminAddWhitelist(String twitchId);
    /** Removes a Twitch id (also an absent one); catapult-api's status (204), 502 when unreachable. */
    int adminDeleteWhitelist(String twitchId);
    /** Flips the invitations; catapult-api's status (204), 502 when unreachable. */
    int adminToggleInvites();
    /** Saves the invitation settings, a null cap meaning unlimited; catapult-api's status (204), 502 when unreachable. */
    int adminSaveInviteSettings(Integer globalMaxMembers, Integer defaultMaxUses, boolean defaultCanReinvite);
    /** Sets an invite's quota, null meaning the default; catapult-api's status (204), 502 when unreachable. */
    int adminSaveInviteQuota(UUID inviteId, Integer maxUses, Boolean canReinvite);

    /** One member, for the targeting view's header; null when unknown or catapult-api fails. */
    AdminMemberSummaryDto adminMember(UUID id);
    /** The member's custom flags and group keys; null when unknown or catapult-api fails. */
    MemberTargetingDto adminMemberTargeting(UUID id);
    /** Sets (or replaces) a custom flag; catapult-api's status (204; 400 blank key, 404 unknown member), 502 when unreachable. */
    int adminSetMemberFlag(UUID id, String key, String value);
    /** Deletes a custom flag; catapult-api's status (204, also for an absent flag; 404 unknown member), 502 when unreachable. */
    int adminDeleteMemberFlag(UUID id, String key);
    /** Adds the member to the group of that key; catapult-api's status (204; 404 unknown member or group), 502 when unreachable. */
    int adminAddMemberToGroup(UUID id, String groupKey);
    /** Removes the member from the group; catapult-api's status (204; 404 unknown group), 502 when unreachable. */
    int adminRemoveMemberFromGroup(UUID id, UUID groupId);
    /** catapult-api's status (202; 400 invalid; 429 rate-limited), 502 when unreachable. */
    int adminBroadcast(BroadcastRequestDto body);

    // --- account deletion ---

    /** The logged-in account as catapult-api validates it (status, deletion request time); null when it fails. */
    UserInfoResponse accountInfo();
    /** Days between a deletion request and the account's removal; 7 when catapult-api gives none. */
    int accountDeletionDelayDays();
    /**
     * Asks for the channel's deletion: catapult-api's status (204, also when {@code confirmUsername} is not
     * the channel's, which it then ignores; 403 not the caller's channel), 502 when unreachable.
     */
    int deleteAccount(String username, String confirmUsername);
    /** Cancels a pending deletion: catapult-api's status (204), 502 when unreachable. */
    int cancelAccountDeletion(String username);

    // --- member invitations ---

    /** The member's invite code, link and redemptions (canInvite false without the right); null when catapult-api fails. */
    InvitePageData invitePage();
    /** Replaces the member's invite code: catapult-api's status (204), 502 when unreachable. */
    int regenerateInvite();

    /** A provider's shared API keys, masked; null when catapult-api fails. */
    ApiKeysPageDto adminApiKeys(ApiKeyProvider provider);
    /** Adds a key (an already listed one changes nothing); catapult-api's status (204; 400 invalid format), 502 when unreachable. */
    int adminAddApiKey(ApiKeyProvider provider, String apiKey);
    /** Removes the key of that id; catapult-api's status (204; 404 unknown), 502 when unreachable. */
    int adminDeleteApiKey(ApiKeyProvider provider, String keyId);
    /** Reloads the provider's keys into its rotator; catapult-api's status (204), 502 when unreachable. */
    int adminRefreshApiKeys(ApiKeyProvider provider);
}
