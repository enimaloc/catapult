package fr.enimaloc.catapult.service.mock;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockWhitelistStoreTest {
    private final MockWhitelistStore store = new MockWhitelistStore();

    @Test
    void add_listsAnIdOnce_andDeleteRemovesIt() {
        assertThat(store.add("777")).isEqualTo(204);
        assertThat(store.add("777")).isEqualTo(204);
        assertThat(store.page().entries().stream().filter(e -> e.twitchId().equals("777"))).hasSize(1);
        assertThat(store.page().resolvedUsernames()).containsEntry("777", "—");

        assertThat(store.delete("777")).isEqualTo(204);
        assertThat(store.delete("777")).isEqualTo(204);
        assertThat(store.page().entries()).noneMatch(e -> e.twitchId().equals("777"));
    }

    @Test
    void toggles_flipTheirSwitch() {
        boolean whitelist = store.page().whitelistEnabled();
        boolean invites = store.page().inviteEnabled();

        store.toggle();
        store.toggleInvites();

        assertThat(store.page().whitelistEnabled()).isEqualTo(!whitelist);
        assertThat(store.page().inviteEnabled()).isEqualTo(!invites);
    }

    @Test
    void settings_keepNullsAsUnlimited_andTheCapReachedFollowsTheEntries() {
        store.saveSettings(null, null, true);
        var page = store.page();
        assertThat(page.globalMaxMembers()).isNull();
        assertThat(page.defaultMaxUses()).isNull();
        assertThat(page.defaultCanReinvite()).isTrue();
        assertThat(page.globalCapReached()).isFalse();

        store.saveSettings(page.entries().size(), 2, false);
        assertThat(store.page().globalCapReached()).isTrue();
    }

    @Test
    void quota_replacesTheInvitesLimits_404ForAnUnknownOne() {
        var invite = store.page().invites().getFirst();

        assertThat(store.saveQuota(invite.id(), null, null)).isEqualTo(204);
        var saved = store.page().invites().getFirst();
        assertThat(saved.maxUses()).isNull();
        assertThat(saved.canReinvite()).isNull();
        assertThat(saved.code()).isEqualTo(invite.code());

        assertThat(store.saveQuota(invite.id(), 0, false)).isEqualTo(204);
        assertThat(store.page().invites().getFirst().maxUses()).isZero();
        assertThat(store.saveQuota(UUID.randomUUID(), 1, true)).isEqualTo(404);
    }
}
