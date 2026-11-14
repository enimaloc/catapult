package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.AnkamaAccountDto;
import fr.enimaloc.catapult.common.dto.admin.AnkamaLoginStartDto;
import fr.enimaloc.catapult.service.ApiService.AdminCall;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockAnkamaAccountsStoreTest {
    private final MockAnkamaAccountsStore store = new MockAnkamaAccountsStore();

    private AnkamaAccountDto find(String authState) {
        return store.list().stream().filter(a -> a.authState().equals(authState)).findFirst().orElseThrow();
    }

    private UUID startLogin(UUID accountId) {
        AdminCall<AnkamaLoginStartDto> started = store.startLogin(accountId);
        assertThat(started.status()).isEqualTo(200);
        assertThat(started.body().authorizeUrl()).startsWith("https://");
        return started.body().loginId();
    }

    private static String redirect(String code) {
        return "http://127.0.0.1:9001/authorized?code=" + code;
    }

    @Test
    void seedHasAReadyAndAReauthRequiredBot() {
        assertThat(find("READY").sessionState()).isEqualTo("READY");
        assertThat(find("REAUTH_REQUIRED").sessionState()).isEqualTo("DISABLED");
    }

    @Test
    void aNewBotAppearsReadyOnceItsRedirectUrlIsPasted() {
        int before = store.list().size();
        UUID loginId = startLogin(null);

        AdminCall<AnkamaAccountDto> done = store.completeLogin(loginId, redirect("abc"));

        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().authState()).isEqualTo("READY");
        assertThat(store.list()).hasSize(before + 1);
    }

    @Test
    void aShieldCodeIsAskedForWhenTheMockLoginSaysSo_andOnlyTheRightCodeValidatesIt() {
        UUID loginId = startLogin(null);
        AnkamaAccountDto bot = store.completeLogin(loginId, redirect(MockAnkamaAccountsStore.SHIELD_LOGIN_CODE)).body();

        assertThat(bot.authState()).isEqualTo("CODE_REQUIRED");
        assertThat(bot.shieldCodeDomain()).isEqualTo("example.com");

        AdminCall<Void> wrong = store.submitCode(bot.id(), "000000");
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.error()).isNotBlank();
        assertThat(store.list()).filteredOn(a -> a.id().equals(bot.id())).singleElement()
                .satisfies(a -> assertThat(a.authState()).isEqualTo("CODE_REQUIRED"));

        assertThat(store.resendCode(bot.id())).isEqualTo(202);
        assertThat(store.submitCode(bot.id(), MockAnkamaAccountsStore.CODE).status()).isEqualTo(202);
        assertThat(store.list()).filteredOn(a -> a.id().equals(bot.id())).singleElement().satisfies(a -> {
            assertThat(a.authState()).isEqualTo("READY");
            assertThat(a.shieldCodeDomain()).isNull();
        });
        assertThat(store.submitCode(bot.id(), MockAnkamaAccountsStore.CODE).status()).isEqualTo(409);
        assertThat(store.resendCode(bot.id())).isEqualTo(409);
        assertThat(store.submitCode(UUID.randomUUID(), "1").status()).isEqualTo(404);
        assertThat(store.resendCode(UUID.randomUUID())).isEqualTo(404);
    }

    @Test
    void reauthenticatingABotKeepsItsIdentity() {
        UUID id = find("REAUTH_REQUIRED").id();
        int before = store.list().size();
        UUID loginId = startLogin(id);

        assertThat(store.completeLogin(loginId, redirect("abc")).body().id()).isEqualTo(id);

        assertThat(store.list()).hasSize(before);
        assertThat(store.list()).filteredOn(a -> a.id().equals(id)).singleElement()
                .satisfies(a -> assertThat(a.authState()).isEqualTo("READY"));
    }

    @Test
    void loginRefusalsAre400WithAMessage_andAnAttemptUsesTheLoginUp() {
        assertThat(store.startLogin(UUID.randomUUID()).status()).isEqualTo(404);

        AdminCall<AnkamaAccountDto> unknown = store.completeLogin(UUID.randomUUID(), redirect("abc"));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.error()).isNotBlank();

        UUID noCode = startLogin(null);
        assertThat(store.completeLogin(noCode, "http://127.0.0.1:9001/authorized").status()).isEqualTo(400);
        assertThat(store.completeLogin(noCode, redirect("abc")).status()).isEqualTo(400); // already used

        UUID refused = startLogin(null);
        AdminCall<AnkamaAccountDto> call = store.completeLogin(refused, redirect(MockAnkamaAccountsStore.REFUSED_LOGIN_CODE));
        assertThat(call.status()).isEqualTo(400);
        assertThat(call.error()).isNotBlank();
        assertThat(store.completeLogin(refused, redirect("abc")).status()).isEqualTo(400);
    }

    @Test
    void patchDeleteAndUnknownBots() {
        UUID id = find("READY").id();

        AdminCall<AnkamaAccountDto> patched = store.patch(id, Map.of("enabled", false, "fillOrder", 5, "friendLimitReached", true));
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body().enabled()).isFalse();
        assertThat(patched.body().fillOrder()).isEqualTo(5);
        assertThat(patched.body().friendLimitReached()).isTrue();
        assertThat(patched.body().sessionState()).isEqualTo("DISABLED");
        assertThat(store.patch(UUID.randomUUID(), Map.of()).status()).isEqualTo(404);

        assertThat(store.delete(id)).isEqualTo(204);
        assertThat(store.delete(id)).isEqualTo(404);
    }
}
