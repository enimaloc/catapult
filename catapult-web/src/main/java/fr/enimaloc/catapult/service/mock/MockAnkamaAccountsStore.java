package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AnkamaAccountDto;
import fr.enimaloc.catapult.common.dto.admin.AnkamaLoginStartDto;
import fr.enimaloc.catapult.service.ApiService.AdminCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The mock's Ankama bot pool: one store for the whole application, answering like catapult-api. A login is started
 * (new bot, or a bot's re-authentication), then finished with the pasted redirect URL, which holds a {@code code}: any
 * code logs the bot in, {@link #SHIELD_LOGIN_CODE} makes Ankama ask for a Shield code ({@code CODE_REQUIRED}, validated
 * by {@link #CODE}), {@link #REFUSED_LOGIN_CODE} is refused. A login is used up by its first completion, whatever the
 * outcome; refusals are 400 with a message, a code for a bot not waiting for one a 409, an unknown bot a 404.
 */
class MockAnkamaAccountsStore {
    static final String CODE = "257146";
    static final String SHIELD_LOGIN_CODE = "shield";
    static final String REFUSED_LOGIN_CODE = "refused";
    private static final String SHIELD_DOMAIN = "example.com";

    private record Account(UUID id, String tag, String authState, String sessionState, int fillOrder,
                           boolean friendLimitReached, boolean enabled, long friendCount, String shieldCodeDomain) {
        AnkamaAccountDto dto() {
            return new AnkamaAccountDto(id, tag, authState, sessionState, fillOrder, friendLimitReached, enabled, friendCount,
                    shieldCodeDomain);
        }

        Account loggedIn(String authState, String shieldCodeDomain) {
            String session = "READY".equals(authState) ? (enabled ? "READY" : "DISABLED") : "DISABLED";
            return new Account(id, tag, authState, session, fillOrder, friendLimitReached, enabled, friendCount, shieldCodeDomain);
        }

        Account with(String sessionState, int fillOrder, boolean friendLimitReached, boolean enabled) {
            return new Account(id, tag, authState, sessionState, fillOrder, friendLimitReached, enabled, friendCount, shieldCodeDomain);
        }
    }

    private final List<Account> accounts = new ArrayList<>(List.of(
            new Account(UUID.fromString("5a1d2f3b-0000-4000-8000-000000000001"), "TestBot#1000", "READY", "READY",
                    0, false, true, 42, null),
            new Account(UUID.fromString("5a1d2f3b-0000-4000-8000-000000000002"), "TestBot#2000", "REAUTH_REQUIRED",
                    "DISABLED", 1, false, true, 0, null)));
    /** The logins started and not yet completed: login id to the bot it re-authenticates (empty for a new bot). */
    private final Map<UUID, Optional<UUID>> logins = new HashMap<>();
    private int created = 0;

    synchronized List<AnkamaAccountDto> list() {
        return accounts.stream().map(Account::dto).toList();
    }

    synchronized AdminCall<AnkamaLoginStartDto> startLogin(UUID accountId) {
        if (accountId != null && indexOf(accountId) < 0) return new AdminCall<>(404, null, null);
        UUID loginId = UUID.randomUUID();
        logins.put(loginId, Optional.ofNullable(accountId));
        return new AdminCall<>(200, new AnkamaLoginStartDto(loginId,
                "https://auth.ankama.com/login/ankama?mock=" + loginId), null);
    }

    synchronized AdminCall<AnkamaAccountDto> completeLogin(UUID loginId, String redirectUrl) {
        Optional<UUID> target = logins.remove(loginId); // used up whatever the outcome
        if (target == null) return refused("This login is unknown or has expired, start it again");
        String code = codeOf(redirectUrl);
        if (code == null) return refused("That address holds no code, copy the address of the page that failed to load");
        if (REFUSED_LOGIN_CODE.equals(code)) return refused("Ankama refused the login, start it again");

        boolean shield = SHIELD_LOGIN_CODE.equals(code);
        String auth = shield ? "CODE_REQUIRED" : "READY";
        String domain = shield ? SHIELD_DOMAIN : null;
        int index = target.map(this::indexOf).orElse(-1);
        Account account;
        if (index >= 0) {
            account = accounts.get(index).loggedIn(auth, domain);
            accounts.set(index, account);
        } else {
            created++;
            account = new Account(UUID.randomUUID(), "MockBot#" + String.format("%04d", created), auth, "READY",
                    accounts.size(), false, true, 0, domain).loggedIn(auth, domain);
            accounts.add(account);
        }
        return new AdminCall<>(200, account.dto(), null);
    }

    synchronized AdminCall<Void> submitCode(UUID id, String code) {
        int index = indexOf(id);
        if (index < 0) return new AdminCall<>(404, null, null);
        Account a = accounts.get(index);
        if (!"CODE_REQUIRED".equals(a.authState())) return new AdminCall<>(409, null, null);
        if (!CODE.equals(code)) return new AdminCall<>(400, null, "That code is not valid, check the e-mail and try again");
        accounts.set(index, a.loggedIn("READY", null));
        return new AdminCall<>(202, null, null);
    }

    synchronized int resendCode(UUID id) {
        int index = indexOf(id);
        if (index < 0) return 404;
        return "CODE_REQUIRED".equals(accounts.get(index).authState()) ? 202 : 409;
    }

    synchronized AdminCall<AnkamaAccountDto> patch(UUID id, Map<String, Object> patch) {
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

    private static AdminCall<AnkamaAccountDto> refused(String message) {
        return new AdminCall<>(400, null, message);
    }

    /** The {@code code} query parameter of the pasted address; null when it has none. */
    private static String codeOf(String redirectUrl) {
        if (redirectUrl == null) return null;
        int query = redirectUrl.indexOf('?');
        if (query < 0) return null;
        for (String pair : redirectUrl.substring(query + 1).split("&")) {
            if (pair.startsWith("code=") && pair.length() > 5) return pair.substring(5);
        }
        return null;
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id().equals(id)) return i;
        }
        return -1;
    }
}
