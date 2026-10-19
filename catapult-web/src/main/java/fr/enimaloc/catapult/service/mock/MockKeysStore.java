package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The mock's Steam and DTDD shared API keys: one store for the whole application. Answers like
 * catapult-api: the same key formats (400 otherwise), an already listed key changes nothing, an
 * unknown key id is a 404, and only the masked form ever leaves the store.
 */
class MockKeysStore {
    private static final Map<ApiKeyProvider, Pattern> FORMATS = Map.of(
            ApiKeyProvider.STEAM, Pattern.compile("[0-9A-Fa-f]{32}"),
            ApiKeyProvider.DTDD, Pattern.compile("ddd_[A-Za-z0-9]{16,128}"));

    private final Map<ApiKeyProvider, List<String>> keys = new EnumMap<>(ApiKeyProvider.class);

    MockKeysStore() {
        keys.put(ApiKeyProvider.STEAM, new ArrayList<>(List.of("0123456789ABCDEF0123456789ABCDEF")));
        keys.put(ApiKeyProvider.DTDD, new ArrayList<>(List.of("ddd_abcdefghijklmnop1234")));
    }

    private static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    /** Stable and not reversible to the key, like catapult-api's hashed id. */
    private static String id(String key) {
        return Integer.toHexString(key.hashCode());
    }

    synchronized ApiKeysPageDto page(ApiKeyProvider provider) {
        return new ApiKeysPageDto(true, keys.get(provider).stream()
                .map(k -> new ApiKeysPageDto.Key(id(k), mask(k), null, false, 0L)).toList());
    }

    synchronized int add(ApiKeyProvider provider, String apiKey) {
        String trimmed = apiKey == null ? "" : apiKey.trim();
        if (!FORMATS.get(provider).matcher(trimmed).matches()) return 400;
        if (!keys.get(provider).contains(trimmed)) keys.get(provider).add(trimmed);
        return 204;
    }

    synchronized int delete(ApiKeyProvider provider, String keyId) {
        return keys.get(provider).removeIf(k -> id(k).equals(keyId)) ? 204 : 404;
    }

    int refresh() {
        return 204;
    }
}
