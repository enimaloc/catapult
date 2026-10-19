package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AccountDto;
import fr.enimaloc.catapult.common.dto.admin.MinecraftDeviceCodeDto;
import fr.enimaloc.catapult.service.http.ApiClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mock's Minecraft service accounts: one store for the whole application, answering like
 * catapult-api. A device code it issued answers 202 to its first create / reauth poll (not
 * confirmed yet) and 200 to the next one, after which it is spent (400, as an unknown one).
 * Deleting an account users are linked to is a 409; an unknown account is a 404.
 */
class MockMinecraftAccountsStore {
    private record Account(UUID id, String label, String minecraftUsername, int fillOrder, int linkCount,
                           boolean enabled, boolean friendLimitReached) {
        AccountDto dto() {
            return new AccountDto(id, label, minecraftUsername, fillOrder, friendLimitReached, enabled, linkCount);
        }
    }

    private final List<Account> accounts = new ArrayList<>(List.of(
            new Account(UUID.fromString("6d2f0c1e-0000-4000-8000-000000000001"), "Bot1", "CatapultBot1", 0, 12, true, false),
            new Account(UUID.fromString("6d2f0c1e-0000-4000-8000-000000000002"), "Bot2", "CatapultBot2", 1, 0, false, false),
            new Account(UUID.fromString("6d2f0c1e-0000-4000-8000-000000000003"), "Bot3", "CatapultBot3", 2, 30, true, true)));
    /** Issued device codes → how many times each was polled. */
    private final Map<String, Integer> polls = new HashMap<>();

    synchronized List<AccountDto> list() {
        return accounts.stream().map(Account::dto).toList();
    }

    synchronized MinecraftDeviceCodeDto deviceCode() {
        String code = UUID.randomUUID().toString();
        polls.put(code, 0);
        return new MinecraftDeviceCodeDto(code, "MOCK-" + code.substring(0, 4).toUpperCase(), "https://www.microsoft.com/link", 5, 900);
    }

    synchronized ApiClient.Result<Map<String, Object>> create(String deviceCode, String label) {
        ApiClient.Result<Map<String, Object>> pending = poll(deviceCode);
        if (pending != null) return pending;
        String name = label == null || label.isBlank() ? "Compte Minecraft" : label;
        Account account = new Account(UUID.randomUUID(), name, "CatapultBot" + (accounts.size() + 1), accounts.size(), 0, true, false);
        accounts.add(account);
        return done(account);
    }

    synchronized ApiClient.Result<Map<String, Object>> reauth(UUID id, String deviceCode) {
        int index = indexOf(id);
        if (index < 0) return new ApiClient.Result<>(404, Map.of("message", "Compte inconnu"));
        ApiClient.Result<Map<String, Object>> pending = poll(deviceCode);
        if (pending != null) return pending;
        Account a = accounts.get(index);
        Account renewed = new Account(a.id(), a.label(), a.minecraftUsername(), a.fillOrder(), a.linkCount(), true, a.friendLimitReached());
        accounts.set(index, renewed);
        return done(renewed);
    }

    synchronized int patch(UUID id, Map<String, Object> patch) {
        int index = indexOf(id);
        if (index < 0) return 404;
        Account a = accounts.get(index);
        boolean enabled = patch.get("enabled") instanceof Boolean b ? b : a.enabled();
        boolean full = patch.get("friendLimitReached") instanceof Boolean b ? b : a.friendLimitReached();
        accounts.set(index, new Account(a.id(), a.label(), a.minecraftUsername(), a.fillOrder(), a.linkCount(), enabled, full));
        return 200;
    }

    synchronized int delete(UUID id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        if (accounts.get(index).linkCount() > 0) return 409;
        accounts.remove(index);
        return 204;
    }

    /** 202 for a code's first poll, 400 for an unknown or spent one; null once it is confirmed (and now spent). */
    private ApiClient.Result<Map<String, Object>> poll(String deviceCode) {
        Integer count = deviceCode == null ? null : polls.get(deviceCode);
        if (count == null) return new ApiClient.Result<>(400, Map.of("message", "Device code inconnu ou expiré"));
        if (count == 0) {
            polls.put(deviceCode, 1);
            return new ApiClient.Result<>(202, Map.of("status", "PENDING"));
        }
        polls.remove(deviceCode);
        return null;
    }

    private static ApiClient.Result<Map<String, Object>> done(Account account) {
        return new ApiClient.Result<>(200, Map.of("id", account.id().toString(), "label", account.label(),
                "minecraftUsername", account.minecraftUsername()));
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id().equals(id)) return i;
        }
        return -1;
    }
}
