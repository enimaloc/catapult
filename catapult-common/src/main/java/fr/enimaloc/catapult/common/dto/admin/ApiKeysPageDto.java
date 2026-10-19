package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * A provider's shared API keys as the admin Keys tab shows them: whether the integration is enabled
 * and each key by its id and masked value only (the raw key never leaves catapult-api), its owner
 * (null for an admin's) and any remaining rate-limit block.
 */
public record ApiKeysPageDto(boolean enabled, List<Key> keys) {
    public record Key(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}

    public static ApiKeysPageDto of(SteamKeysPageData page) {
        return new ApiKeysPageDto(page.steamEnabled(), page.keys().stream()
                .map(k -> new Key(k.id(), k.masked(), k.owner(), k.blocked(), k.blockedForSeconds())).toList());
    }

    public static ApiKeysPageDto of(DtddKeysPageData page) {
        return new ApiKeysPageDto(page.dtddEnabled(), page.keys().stream()
                .map(k -> new Key(k.id(), k.masked(), k.owner(), k.blocked(), k.blockedForSeconds())).toList());
    }
}
