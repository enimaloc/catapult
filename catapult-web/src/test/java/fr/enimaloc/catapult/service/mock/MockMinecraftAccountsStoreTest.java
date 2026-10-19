package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.MinecraftAccountDto;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockMinecraftAccountsStoreTest {
    private final MockMinecraftAccountsStore store = new MockMinecraftAccountsStore();

    private MinecraftAccountDto find(java.util.function.Predicate<MinecraftAccountDto> filter) {
        return store.list().stream().filter(filter).findFirst().orElseThrow();
    }

    @Test
    void seed_coversEveryState() {
        assertThat(store.list()).anyMatch(MinecraftAccountDto::enabled)
                .anyMatch(a -> !a.enabled())
                .anyMatch(MinecraftAccountDto::friendLimitReached)
                .anyMatch(a -> a.linkCount() > 0)
                .anyMatch(a -> a.linkCount() == 0);
    }

    @Test
    void create_answers202ForTheFirstPoll_then200_andListsTheAccount() {
        var code = store.deviceCode();
        assertThat(code.verificationUri()).startsWith("https://");
        int before = store.list().size();

        assertThat(store.create(code.deviceCode(), "Bot9").status()).isEqualTo(202);
        var done = store.create(code.deviceCode(), "Bot9");

        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body()).containsEntry("label", "Bot9").containsKey("minecraftUsername");
        assertThat(store.list()).hasSize(before + 1).anyMatch(a -> a.label().equals("Bot9") && a.enabled());
        assertThat(store.create(code.deviceCode(), "Bot9").status()).isEqualTo(400); // a used code is gone
        assertThat(store.create("never-issued", "x").status()).isEqualTo(400);
    }

    @Test
    void reauth_reEnablesTheAccount_andAnUnknownOneIs404() {
        var disabled = find(a -> !a.enabled());
        var code = store.deviceCode();

        assertThat(store.reauth(UUID.randomUUID(), code.deviceCode()).status()).isEqualTo(404);
        assertThat(store.reauth(disabled.id(), code.deviceCode()).status()).isEqualTo(202);
        assertThat(store.reauth(disabled.id(), code.deviceCode()).status()).isEqualTo(200);
        assertThat(find(a -> a.id().equals(disabled.id())).enabled()).isTrue();
    }

    @Test
    void patch_andDelete_answerLikeCatapultApi() {
        var full = find(MinecraftAccountDto::friendLimitReached);
        var linked = find(a -> a.linkCount() > 0);
        var free = find(a -> a.linkCount() == 0);

        assertThat(store.patch(full.id(), Map.of("friendLimitReached", false))).isEqualTo(200);
        assertThat(find(a -> a.id().equals(full.id())).friendLimitReached()).isFalse();
        assertThat(store.patch(free.id(), Map.of("enabled", false))).isEqualTo(200);
        assertThat(find(a -> a.id().equals(free.id())).enabled()).isFalse();
        assertThat(store.patch(UUID.randomUUID(), Map.of("enabled", true))).isEqualTo(404);

        assertThat(store.delete(linked.id())).isEqualTo(409);
        assertThat(store.delete(free.id())).isEqualTo(204);
        assertThat(store.delete(free.id())).isEqualTo(404);
    }
}
