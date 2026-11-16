package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.EpicAccountDto;
import fr.enimaloc.catapult.common.dto.admin.EpicAccountsDto;
import fr.enimaloc.catapult.service.ApiService.AdminCall;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mock's Epic bot pool: one store for the whole application, answering like catapult-api. A bot is added, or
 * logged in again, with what the admin pasted: the whole JSON Epic's page shows or its 32-hex {@code authorizationCode}
 * alone. Any code logs the bot in, except {@link #EXPIRED_CODE} (refused as expired or used),
 * {@link #OTHER_ACCOUNT_CODE} (another Epic account than the one to re-authenticate) and a page opened logged out
 * ({@code "authorizationCode": null}). Refusals are 400 with a message, an unknown bot a 404.
 */
class MockEpicAccountsStore {
    static final String CODE_URL =
            "https://www.epicgames.com/id/api/redirect?clientId=34a02cf8f4414e29b15921876da36f9a&responseType=code";
    static final String EXPIRED_CODE = "0".repeat(32);
    static final String OTHER_ACCOUNT_CODE = "1".repeat(32);

    static final String LOG_IN_FIRST = "log in with the bot account first, then reload the page";
    static final String NO_CODE = "no authorization code found";
    static final String CODE_EXPIRED = "this code expired or was already used, get a new one";
    static final String NOT_SAME_ACCOUNT = "this is not the same Epic account";

    private static final Pattern NULL_CODE = Pattern.compile("\"authorizationCode\"\\s*:\\s*null");
    private static final Pattern JSON_CODE =
            Pattern.compile("\"authorizationCode\"\\s*:\\s*\"\\s*([0-9a-fA-F]{32})\\s*\"");
    private static final Pattern BARE_CODE = Pattern.compile("[0-9a-fA-F]{32}");

    private record Account(UUID id, String displayName, String authState, String sessionState, int fillOrder,
                           boolean friendLimitReached, boolean enabled, long friendCount, Instant refreshExpiresAt,
                           boolean refreshExpiresSoon) {
        EpicAccountDto dto() {
            return new EpicAccountDto(id, displayName, authState, sessionState, fillOrder, friendLimitReached, enabled,
                    friendCount, refreshExpiresAt, refreshExpiresSoon);
        }

        Account loggedIn() {
            Instant expires = Instant.now().plus(60, ChronoUnit.DAYS);
            return new Account(id, displayName, "READY", enabled ? "READY" : "DISABLED", fillOrder,
                    friendLimitReached, enabled, friendCount, expires, false);
        }

        Account with(String sessionState, int fillOrder, boolean friendLimitReached, boolean enabled) {
            return new Account(id, displayName, authState, sessionState, fillOrder, friendLimitReached, enabled,
                    friendCount, refreshExpiresAt, refreshExpiresSoon);
        }
    }

    private final List<Account> accounts = new ArrayList<>(List.of(
            new Account(UUID.fromString("e91c0000-0000-4000-8000-000000000001"), "TestEpicBot1", "READY", "READY",
                    0, false, true, 42, Instant.now().plus(60, ChronoUnit.DAYS), false),
            new Account(UUID.fromString("e91c0000-0000-4000-8000-000000000002"), "TestEpicBot2", "READY", "READY",
                    1, false, true, 7, Instant.now().plus(10, ChronoUnit.DAYS), true),
            new Account(UUID.fromString("e91c0000-0000-4000-8000-000000000003"), "TestEpicBot3", "REAUTH_REQUIRED",
                    "DISABLED", 2, false, true, 0, null, false)));
    private int created = 0;

    synchronized EpicAccountsDto list() {
        return new EpicAccountsDto(accounts.stream().map(Account::dto).toList(), CODE_URL);
    }

    synchronized AdminCall<EpicAccountDto> addOrReauth(String pasted, UUID reauthAccountId) {
        String code;
        try {
            code = codeFrom(pasted);
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        }
        int reauth = reauthAccountId == null ? -1 : indexOf(reauthAccountId);
        if (reauthAccountId != null && reauth < 0) return new AdminCall<>(404, null, null);
        if (EXPIRED_CODE.equals(code)) return refused(CODE_EXPIRED);
        if (OTHER_ACCOUNT_CODE.equals(code) && reauth >= 0) return refused(NOT_SAME_ACCOUNT);

        Account account;
        if (reauth >= 0) {
            account = accounts.get(reauth).loggedIn();
            accounts.set(reauth, account);
        } else {
            created++;
            account = new Account(UUID.randomUUID(), "MockEpicBot" + created, "READY", "READY", accounts.size(), false,
                    true, 0, null, false).loggedIn();
            accounts.add(account);
        }
        return new AdminCall<>(200, account.dto(), null);
    }

    synchronized AdminCall<EpicAccountDto> patch(UUID id, Map<String, Object> patch) {
        int index = indexOf(id);
        if (index < 0) return new AdminCall<>(404, null, null);
        Account a = accounts.get(index);
        boolean enabled = patch.get("enabled") instanceof Boolean b ? b : a.enabled();
        boolean full = patch.get("friendLimitReached") instanceof Boolean b ? b : a.friendLimitReached();
        int order = patch.get("fillOrder") instanceof Number n ? n.intValue() : a.fillOrder();
        String session = enabled == a.enabled() ? a.sessionState() : enabled ? "CONNECTING" : "DISABLED";
        Account patched = a.with(session, order, full, enabled);
        accounts.set(index, patched);
        return new AdminCall<>(200, patched.dto(), null);
    }

    synchronized int delete(UUID id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        accounts.remove(index);
        return 204;
    }

    private static AdminCall<EpicAccountDto> refused(String message) {
        return new AdminCall<>(400, null, message);
    }

    /** The authorization code in what was pasted, as catapult-api reads it. */
    private static String codeFrom(String pasted) {
        String text = pasted == null ? "" : pasted.strip();
        Matcher json = JSON_CODE.matcher(text);
        if (json.find()) return json.group(1).toLowerCase();
        if (NULL_CODE.matcher(text).find()) throw new IllegalArgumentException(LOG_IN_FIRST);
        String bare = text.replace("\"", "").replace("'", "").strip();
        if (BARE_CODE.matcher(bare).matches()) return bare.toLowerCase();
        throw new IllegalArgumentException(NO_CODE);
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id().equals(id)) return i;
        }
        return -1;
    }
}
