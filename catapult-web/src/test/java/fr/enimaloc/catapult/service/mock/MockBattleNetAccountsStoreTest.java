package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.BattleNetAccountDto;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockBattleNetAccountsStoreTest {
    private final MockBattleNetAccountsStore store = new MockBattleNetAccountsStore();

    private BattleNetAccountDto find(String authState) {
        return store.list().stream().filter(a -> a.authState().equals(authState)).findFirst().orElseThrow();
    }

    @Test
    void seedHasAReadyAndACodeRequiredAccount() {
        assertThat(find("READY").sessionState()).isEqualTo("READY");
        assertThat(find("CODE_REQUIRED").battleTag()).isNull();
    }

    @Test
    void theRightCodeMovesTheAccountToReady_aWrongOneLeavesItWaiting() {
        UUID id = find("CODE_REQUIRED").id();

        assertThat(store.submitCode(id, "000000")).isEqualTo(202);
        assertThat(find("CODE_REQUIRED").id()).isEqualTo(id);

        assertThat(store.submitCode(id, MockBattleNetAccountsStore.CODE)).isEqualTo(202);
        assertThat(store.list()).filteredOn(a -> a.id().equals(id)).singleElement().satisfies(a -> {
            assertThat(a.authState()).isEqualTo("READY");
            assertThat(a.battleTag()).isNotNull();
        });
        assertThat(store.submitCode(id, MockBattleNetAccountsStore.CODE)).isEqualTo(409); // nothing waits any more
        assertThat(store.submitCode(UUID.randomUUID(), "1")).isEqualTo(404);
    }

    @Test
    void createWaitsForACode_reauthPatchAndDelete() {
        assertThat(store.create("Bot9")).isEqualTo(202);
        BattleNetAccountDto created = store.list().stream().filter(a -> a.label().equals("Bot9")).findFirst().orElseThrow();
        assertThat(created.authState()).isEqualTo("CODE_REQUIRED");

        UUID ready = find("READY").id();
        assertThat(store.reauth(ready)).isEqualTo(202);
        assertThat(store.list()).filteredOn(a -> a.id().equals(ready)).singleElement()
                .satisfies(a -> assertThat(a.authState()).isEqualTo("CODE_REQUIRED"));

        assertThat(store.patch(created.id(), Map.of("enabled", false, "friendLimitReached", true))).isEqualTo(200);
        assertThat(store.list()).filteredOn(a -> a.id().equals(created.id())).singleElement().satisfies(a -> {
            assertThat(a.enabled()).isFalse();
            assertThat(a.friendLimitReached()).isTrue();
            assertThat(a.sessionState()).isEqualTo("DISABLED");
        });
        assertThat(store.delete(created.id())).isEqualTo(204);
        assertThat(store.delete(created.id())).isEqualTo(404);
        assertThat(store.patch(created.id(), Map.of())).isEqualTo(404);
    }
}
