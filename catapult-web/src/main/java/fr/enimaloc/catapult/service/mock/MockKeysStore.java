package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.ApiKeyProvider;
import fr.enimaloc.catapult.common.dto.admin.ApiKeysPageDto;
import fr.enimaloc.catapult.common.dto.admin.KeyUsageDto;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The mock's Steam, DTDD, SteamGridDB, IsThereAnyDeal, OpenCritic, RetroAchievements, Riot and FACEIT shared API keys: one store for the whole application. Answers like
 * catapult-api: the same key formats (400 otherwise), an already listed key changes nothing, an
 * unknown key id is a 404, and only the masked form ever leaves the store.
 */
class MockKeysStore {
    /** The shape an OAuth client secret takes (osu!, Bungie): checked like the API does. */
    private static final Pattern SECRET_SHAPE = Pattern.compile("[A-Za-z0-9._~+/=-]{16,128}");
    private static final Map<ApiKeyProvider, Pattern> FORMATS = Map.of(
            ApiKeyProvider.STEAM, Pattern.compile("[0-9A-Fa-f]{32}"),
            ApiKeyProvider.DTDD, Pattern.compile("ddd_[A-Za-z0-9]{16,128}"),
            ApiKeyProvider.STEAMGRIDDB, Pattern.compile("[0-9A-Fa-f]{32}"),
            ApiKeyProvider.ITAD, Pattern.compile("[0-9A-Fa-f]{40}"),
            ApiKeyProvider.OPENCRITIC, Pattern.compile("[A-Za-z0-9]{40,60}"),
            ApiKeyProvider.RETROACHIEVEMENTS, Pattern.compile("[A-Za-z0-9]{16,64}"),
            ApiKeyProvider.RIOT, Pattern.compile(
                    "RGAPI-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
            ApiKeyProvider.FACEIT, Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));

    private final Map<ApiKeyProvider, List<String>> keys = new EnumMap<>(ApiKeyProvider.class);

    MockKeysStore() {
        keys.put(ApiKeyProvider.STEAM, new ArrayList<>(List.of("0123456789ABCDEF0123456789ABCDEF")));
        keys.put(ApiKeyProvider.DTDD, new ArrayList<>(List.of("ddd_abcdefghijklmnop1234")));
        keys.put(ApiKeyProvider.STEAMGRIDDB, new ArrayList<>(List.of("fedcba9876543210FEDCBA9876543210")));
        keys.put(ApiKeyProvider.ITAD, new ArrayList<>(List.of("fedcba9876543210FEDCBA9876543210fedcba98")));
        keys.put(ApiKeyProvider.OPENCRITIC,
                new ArrayList<>(List.of("MockRapidApiKey0123456789MockRapidApiKey0123456789")));
        keys.put(ApiKeyProvider.RETROACHIEVEMENTS, new ArrayList<>(List.of("MockRaKey0123456789abcdefghijklmn")));
        keys.put(ApiKeyProvider.RIOT, new ArrayList<>(List.of("RGAPI-00000000-0000-4000-8000-000000000000")));
        keys.put(ApiKeyProvider.FACEIT, new ArrayList<>(List.of("00000000-0000-4000-8000-0000000fac17")));
    }

    private static String mask(String key) {
        return key.length() > 8 ? key.substring(0, 4) + "…" + key.substring(key.length() - 4) : "…";
    }

    /** Stable and not reversible to the key, like catapult-api's hashed id. */
    private static String id(String key) {
        return Integer.toHexString(key.hashCode());
    }

    /** A key's usage moving with the clock, so that the tab's live update shows. */
    private static KeyUsageDto usage(ApiKeyProvider provider) {
        long tick = System.currentTimeMillis() / 3000;
        int window = (int) (tick % 4);
        if (provider == ApiKeyProvider.STEAMGRIDDB || provider == ApiKeyProvider.ITAD
                || provider == ApiKeyProvider.OPENCRITIC || provider == ApiKeyProvider.RETROACHIEVEMENTS
                || provider == ApiKeyProvider.RIOT
                || provider == ApiKeyProvider.FACEIT) {
            return new KeyUsageDto(window, null, 60, null, "DAY", 40 + tick % 20, null, null);
        }
        return provider == ApiKeyProvider.STEAM
                ? new KeyUsageDto(window % 3, 3, 5, null, "DAY", 1200 + tick % 100, 100_000L, null)
                : new KeyUsageDto(window, 30, 60, 30 - window, "MONTH", 120 + tick % 50, 5000L,
                        (int) (4880 - tick % 50));
    }

    synchronized ApiKeysPageDto page(ApiKeyProvider provider) {
        return new ApiKeysPageDto(true, keys.get(provider).stream()
                .map(k -> new ApiKeysPageDto.Key(id(k), mask(k), null, false, 0L, usage(provider))).toList());
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

    /** The mock's osu! OAuth application: set from the start, so that its card shows filled. */
    private String osuClientId = "12345";

    synchronized fr.enimaloc.catapult.common.dto.admin.OsuCredentialsData osu() {
        return osuClientId == null ? fr.enimaloc.catapult.common.dto.admin.OsuCredentialsData.none()
                : new fr.enimaloc.catapult.common.dto.admin.OsuCredentialsData(true, osuClientId,
                        java.time.Instant.parse("2026-11-20T10:00:00Z"));
    }

    /** Like catapult-api: a malformed value, or a blank secret with nothing set yet or another client, is a 400. */
    synchronized int saveOsu(String clientId, String clientSecret) {
        String id = clientId == null ? "" : clientId.trim();
        String secret = clientSecret == null ? "" : clientSecret.trim();
        if (!id.matches("[0-9]{1,20}") || (!secret.isEmpty() && !SECRET_SHAPE.matcher(secret).matches())) {
            return 400;
        }
        if ((osuClientId == null || !osuClientId.equals(id)) && secret.isEmpty()) return 400;
        osuClientId = id;
        return 204;
    }

    synchronized int deleteOsu() {
        osuClientId = null;
        return 204;
    }

    /** The mock's Bungie.net application: set from the start, so that its card shows filled. */
    private String bungieClientId = "12345";
    private String bungieApiKey = "0123456789abcdef0123456789abcdef";

    synchronized fr.enimaloc.catapult.common.dto.admin.BungieCredentialsData bungie() {
        return bungieClientId == null ? fr.enimaloc.catapult.common.dto.admin.BungieCredentialsData.none()
                : new fr.enimaloc.catapult.common.dto.admin.BungieCredentialsData(true, bungieClientId,
                        mask(bungieApiKey), java.time.Instant.parse("2026-11-20T10:00:00Z"));
    }

    /** Like catapult-api: a malformed value, or a blank secret or key with nothing set yet, is a 400. */
    synchronized int saveBungie(String clientId, String clientSecret, String apiKey) {
        String id = clientId == null ? "" : clientId.trim();
        String secret = clientSecret == null ? "" : clientSecret.trim();
        String key = apiKey == null ? "" : apiKey.trim();
        if (!id.matches("[0-9]{1,20}") || (!secret.isEmpty() && !SECRET_SHAPE.matcher(secret).matches())
                || (!key.isEmpty() && !key.matches("[0-9A-Fa-f]{32}"))) {
            return 400;
        }
        if ((bungieClientId == null || !bungieClientId.equals(id)) && (secret.isEmpty() || key.isEmpty())) return 400;
        bungieClientId = id;
        if (!key.isEmpty()) bungieApiKey = key;
        return 204;
    }

    synchronized int deleteBungie() {
        bungieClientId = null;
        bungieApiKey = null;
        return 204;
    }
}
