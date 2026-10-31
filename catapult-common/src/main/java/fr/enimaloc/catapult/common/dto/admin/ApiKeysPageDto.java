package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;
import java.util.Objects;

/**
 * A provider's shared API keys as the admin Keys tab shows them: whether the integration is enabled
 * and each key by its id and masked value only (the raw key never leaves catapult-api), its owner
 * (null for an admin's) and any remaining rate-limit block. Built from a provider's page: none
 * (catapult-api unreachable) gives null, a page without its key list no keys.
 */
public record ApiKeysPageDto(boolean enabled, List<Key> keys) {
    public record Key(String id, String masked, String owner, boolean blocked, long blockedForSeconds) {}

    public ApiKeysPageDto {
        keys = keys == null ? List.of() : keys;
    }

    public static ApiKeysPageDto of(SteamKeysPageData page) {
        return page == null ? null : new ApiKeysPageDto(page.steamEnabled(), keys(page.keys()));
    }

    public static ApiKeysPageDto of(DtddKeysPageData page) {
        return page == null ? null : new ApiKeysPageDto(page.dtddEnabled(), keys(page.keys()));
    }

    private static List<Key> keys(List<KeyStatus> keys) {
        return keys == null ? List.of() : keys.stream()
                .filter(Objects::nonNull)
                .map(k -> new Key(k.id(), k.masked(), k.owner(), k.blocked(), k.blockedForSeconds()))
                .toList();
    }
}
