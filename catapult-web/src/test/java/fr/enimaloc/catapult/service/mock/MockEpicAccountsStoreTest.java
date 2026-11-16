package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.EpicAccountDto;
import fr.enimaloc.catapult.service.ApiService.AdminCall;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockEpicAccountsStoreTest {
    private static final String CODE = "0123456789abcdef0123456789abcdef";

    private final MockEpicAccountsStore store = new MockEpicAccountsStore();

    private EpicAccountDto find(String authState) {
        return store.list().accounts().stream().filter(a -> a.authState().equals(authState)).findFirst().orElseThrow();
    }

    @Test
    void listCarriesTheCodePageAddress() {
        assertThat(store.list().codeUrl()).startsWith("https://www.epicgames.com/id/api/redirect");
    }

    @Test
    void seedHasAReadyABotThatExpiresSoonAndAReauthRequiredBot() {
        assertThat(find("READY").sessionState()).isEqualTo("READY");
        assertThat(store.list().accounts()).anyMatch(EpicAccountDto::refreshExpiresSoon);
        assertThat(find("REAUTH_REQUIRED").sessionState()).isEqualTo("DISABLED");
    }

    @Test
    void aNewBotAppearsReadyOnceItsCodeIsPasted_whetherBareOrInsideTheWholeText() {
        int before = store.list().accounts().size();

        AdminCall<EpicAccountDto> bare = store.addOrReauth(" \"" + CODE + "\" ", null);
        AdminCall<EpicAccountDto> whole = store.addOrReauth(
                "{\"redirectUrl\":\"x\",\"authorizationCode\":\"" + CODE + "\",\"sid\":null}", null);

        assertThat(bare.status()).isEqualTo(200);
        assertThat(bare.body().authState()).isEqualTo("READY");
        assertThat(bare.body().refreshExpiresAt()).isNotNull();
        assertThat(whole.status()).isEqualTo(200);
        assertThat(store.list().accounts()).hasSize(before + 2);
    }

    @Test
    void aReauthLogsTheSameBotInAgain() {
        EpicAccountDto bot = find("REAUTH_REQUIRED");

        AdminCall<EpicAccountDto> done = store.addOrReauth(CODE, bot.id());

        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().id()).isEqualTo(bot.id());
        assertThat(done.body().authState()).isEqualTo("READY");
        assertThat(store.list().accounts()).filteredOn(a -> a.id().equals(bot.id())).singleElement()
                .satisfies(a -> assertThat(a.authState()).isEqualTo("READY"));
    }

    @Test
    void refusalsAre400WithAMessage() {
        int before = store.list().accounts().size();

        assertThat(store.addOrReauth("nothing here", null)).satisfies(c -> {
            assertThat(c.status()).isEqualTo(400);
            assertThat(c.error()).isEqualTo(MockEpicAccountsStore.NO_CODE);
        });
        assertThat(store.addOrReauth(null, null).error()).isEqualTo(MockEpicAccountsStore.NO_CODE);
        assertThat(store.addOrReauth("{\"authorizationCode\": null}", null)).satisfies(c -> {
            assertThat(c.status()).isEqualTo(400);
            assertThat(c.error()).isEqualTo(MockEpicAccountsStore.LOG_IN_FIRST);
        });
        assertThat(store.addOrReauth(MockEpicAccountsStore.EXPIRED_CODE, null)).satisfies(c -> {
            assertThat(c.status()).isEqualTo(400);
            assertThat(c.error()).isEqualTo(MockEpicAccountsStore.CODE_EXPIRED);
        });
        assertThat(store.addOrReauth(MockEpicAccountsStore.OTHER_ACCOUNT_CODE, find("REAUTH_REQUIRED").id()))
                .satisfies(c -> {
                    assertThat(c.status()).isEqualTo(400);
                    assertThat(c.error()).isEqualTo(MockEpicAccountsStore.NOT_SAME_ACCOUNT);
                });
        assertThat(store.list().accounts()).hasSize(before);
        assertThat(find("REAUTH_REQUIRED").authState()).isEqualTo("REAUTH_REQUIRED");
    }

    @Test
    void anUnknownBotIsA404() {
        UUID unknown = UUID.randomUUID();

        assertThat(store.addOrReauth(CODE, unknown).status()).isEqualTo(404);
        assertThat(store.patch(unknown, Map.of("enabled", false)).status()).isEqualTo(404);
        assertThat(store.delete(unknown)).isEqualTo(404);
    }

    @Test
    void patchTogglesLiftsAndReordersThenDeleteRemoves() {
        EpicAccountDto bot = find("READY");

        assertThat(store.patch(bot.id(), Map.of("enabled", false)).body())
                .satisfies(a -> {
                    assertThat(a.enabled()).isFalse();
                    assertThat(a.sessionState()).isEqualTo("DISABLED");
                });
        assertThat(store.patch(bot.id(), Map.of("friendLimitReached", true, "fillOrder", 9)).body())
                .satisfies(a -> {
                    assertThat(a.friendLimitReached()).isTrue();
                    assertThat(a.fillOrder()).isEqualTo(9);
                });
        assertThat(store.delete(bot.id())).isEqualTo(204);
        assertThat(store.list().accounts()).noneMatch(a -> a.id().equals(bot.id()));
    }
}
