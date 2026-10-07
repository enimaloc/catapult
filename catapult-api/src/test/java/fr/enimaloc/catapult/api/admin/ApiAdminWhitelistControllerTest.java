package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.AddRequest;
import fr.enimaloc.catapult.common.dto.AdminWhitelistQuotaRequest;
import fr.enimaloc.catapult.common.dto.AdminWhitelistRedemptionDto;
import fr.enimaloc.catapult.common.dto.InviteSettingsRequest;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.access.WhitelistEntry;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.access.InviteService;
import fr.enimaloc.catapult.service.access.WhitelistService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The alpha whitelist and invite administration. */
class ApiAdminWhitelistControllerTest {

    private final WhitelistService whitelist = mock(WhitelistService.class);
    private final InviteService invites = mock(InviteService.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final ApiAdminWhitelistController controller = new ApiAdminWhitelistController(whitelist, invites, accounts);

    private static WhitelistEntry entry(String twitchId) {
        WhitelistEntry entry = new WhitelistEntry();
        entry.setTwitchId(twitchId);
        return entry;
    }

    @Test
    void page_resolvesUsernames_andListsInvitesWithTheirRedemptions() {
        when(whitelist.findAll()).thenReturn(List.of(entry("tw-1"), entry("tw-2")));
        UserAccount known = new UserAccount();
        known.setId(UUID.randomUUID());
        known.setTwitchUsername("known");
        when(accounts.findByTwitchId("tw-1")).thenReturn(Optional.of(known));
        when(accounts.findByTwitchId("tw-2")).thenReturn(Optional.empty());
        AlphaInvite invite = new AlphaInvite();
        invite.setId(UUID.randomUUID());
        invite.setOwner(known);
        invite.setCode("CODE");
        invite.setMaxUses(3);
        invite.setUseCount(1);
        AlphaInviteRedemption redemption = new AlphaInviteRedemption();
        redemption.setInviteeTwitchId("tw-3");
        Instant redeemedAt = Instant.parse("2026-01-01T00:00:00Z");
        redemption.setRedeemedAt(redeemedAt);
        when(invites.findAll()).thenReturn(List.of(invite));
        when(invites.getRedemptions(invite)).thenReturn(List.of(redemption));
        when(whitelist.isEnabled()).thenReturn(true);
        when(invites.isInviteEnabled()).thenReturn(true);
        when(invites.getGlobalMaxMembers()).thenReturn(Optional.of(50));
        when(invites.getDefaultMaxUses()).thenReturn(Optional.empty());

        ApiAdminWhitelistController.WhitelistPageData page = controller.page();

        assertThat(page.resolvedUsernames()).containsEntry("tw-1", "known").containsEntry("tw-2", "—");
        assertThat(page.whitelistEnabled()).isTrue();
        assertThat(page.globalMaxMembers()).isEqualTo(50);
        assertThat(page.defaultMaxUses()).isNull();
        assertThat(page.invites()).singleElement().satisfies(row -> {
            assertThat(row.ownerUsername()).isEqualTo("known");
            assertThat(row.code()).isEqualTo("CODE");
            assertThat(row.useCount()).isEqualTo(1);
            assertThat(row.redemptions()).containsExactly(new AdminWhitelistRedemptionDto("tw-3", redeemedAt));
        });
    }

    @Test
    void whitelistEdits() {
        controller.toggle();
        controller.add(new AddRequest(" tw-9 "));
        controller.delete("tw-9");

        verify(whitelist).toggle();
        verify(whitelist).add("tw-9");
        verify(whitelist).remove("tw-9");
    }

    @Test
    void inviteSettings() {
        when(invites.isInviteEnabled()).thenReturn(true);
        UUID inviteId = UUID.randomUUID();

        controller.toggleInvites();
        controller.updateInviteSettings(new InviteSettingsRequest(100, 2, true));
        controller.updateInviteQuota(inviteId, new AdminWhitelistQuotaRequest(5, false));

        verify(invites).setInviteEnabled(false);
        verify(invites).setGlobalMaxMembers(100);
        verify(invites).setDefaultMaxUses(2);
        verify(invites).setDefaultCanReinvite(true);
        verify(invites).updateInviteQuota(inviteId, 5, false);
    }
}
