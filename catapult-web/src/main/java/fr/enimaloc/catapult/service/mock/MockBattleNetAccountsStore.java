package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.BattleNetAccountDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mock's Battle.net bot pool: one store for the whole application, answering like catapult-api.
 * A new account waits for a 2FA code ({@code CODE_REQUIRED}); {@link #CODE} moves it to {@code READY}
 * and any other code leaves it waiting. A code for an account that isn't waiting is a 409, an unknown
 * account a 404. Passwords are accepted and dropped; a bot is shown by its BattleTag, else its masked e-mail.
 */
class MockBattleNetAccountsStore {
    static final String CODE = "123456";

    private record Account(UUID id, String email, String battleTag, String authState, String sessionState,
                           int fillOrder, boolean friendLimitReached, boolean enabled, long friendCount) {
        BattleNetAccountDto dto() {
            // the mock's CODE_REQUIRED bots always have a login waiting for the code
            return new BattleNetAccountDto(id, battleTag != null ? battleTag : masked(email), battleTag, authState, sessionState, fillOrder,
                    friendLimitReached, enabled, friendCount, "CODE_REQUIRED".equals(authState));
        }

        Account with(String authState, String sessionState, boolean friendLimitReached, boolean enabled) {
            return new Account(id, email, battleTag, authState, sessionState, fillOrder, friendLimitReached, enabled, friendCount);
        }
    }

    private final List<Account> accounts = new ArrayList<>(List.of(
            new Account(UUID.fromString("7b3a1d2f-0000-4000-8000-000000000001"), "testbot@example.com", "TestBot#1000",
                    "READY", "READY", 0, false, true, 42),
            new Account(UUID.fromString("7b3a1d2f-0000-4000-8000-000000000002"), "testbot2@example.com", null,
                    "CODE_REQUIRED", "STOPPED_CODE_REQUIRED", 1, false, true, 0)));

    synchronized List<BattleNetAccountDto> list() {
        return accounts.stream().map(Account::dto).toList();
    }

    synchronized int create(String email) {
        if (email == null || email.isBlank()) return 400;
        accounts.add(new Account(UUID.randomUUID(), email.trim(), null, "CODE_REQUIRED", "STOPPED_CODE_REQUIRED",
                accounts.size(), false, true, 0));
        return 202;
    }

    synchronized int submitCode(UUID id, String code) {
        int index = indexOf(id);
        if (index < 0) return 404;
        Account a = accounts.get(index);
        if (!"CODE_REQUIRED".equals(a.authState())) return 409;
        if (CODE.equals(code)) {
            String tag = a.battleTag() == null ? a.email().substring(0, Math.max(1, a.email().indexOf('@'))) + "#0001" : a.battleTag();
            accounts.set(index, new Account(a.id(), a.email(), tag,
                    "READY", "READY", a.fillOrder(), a.friendLimitReached(), a.enabled(), a.friendCount()));
        }
        return 202;
    }

    synchronized int reauth(UUID id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        Account a = accounts.get(index);
        if ("NEW".equals(a.authState())) return 409;
        accounts.set(index, a.with("CODE_REQUIRED", "STOPPED_CODE_REQUIRED", a.friendLimitReached(), a.enabled()));
        return 202;
    }

    synchronized int patch(UUID id, Map<String, Object> patch) {
        int index = indexOf(id);
        if (index < 0) return 404;
        Account a = accounts.get(index);
        boolean enabled = patch.get("enabled") instanceof Boolean b ? b : a.enabled();
        boolean full = patch.get("friendLimitReached") instanceof Boolean b ? b : a.friendLimitReached();
        String session = enabled == a.enabled() ? a.sessionState() : enabled ? "CONNECTING" : "DISABLED";
        accounts.set(index, a.with(a.authState(), session, full, enabled));
        return 200;
    }

    synchronized int delete(UUID id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        accounts.remove(index);
        return 204;
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /** Like catapult-api: {@code bot@example.com} → {@code b***@example.com}. */
    private static String masked(String email) {
        int at = email.lastIndexOf('@');
        return email.charAt(0) + "***" + (at <= 0 ? "" : email.substring(at));
    }
}
