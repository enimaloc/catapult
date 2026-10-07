package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.admin.ApiAdminInviteController;
import fr.enimaloc.catapult.common.dto.AdminInviteQuotaRequest;
import fr.enimaloc.catapult.common.dto.GlobalSettingsRequest;
import fr.enimaloc.catapult.common.dto.MemberDto;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.access.InviteService;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InviteControllersTest {

    private final InviteService inviteService = mock(InviteService.class);

    private static UserAccount member(String username) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchUsername(username);
        user.setStatus(UserAccount.Status.ACTIVE);
        return user;
    }

    private static AlphaInvite inviteOf(UserAccount owner) {
        AlphaInvite invite = new AlphaInvite();
        invite.setId(UUID.randomUUID());
        invite.setOwner(owner);
        invite.setCode("ABCDEFGHJK");
        invite.setUseCount(2);
        return invite;
    }

    private static AlphaInviteRedemption redemption(String twitchId) {
        AlphaInviteRedemption redemption = new AlphaInviteRedemption();
        redemption.setInviteeTwitchId(twitchId);
        return redemption;
    }

    @Nested
    class Member {
        private final ApiUserResolver users = mock(ApiUserResolver.class);
        private final ExperimentService experiments = mock(ExperimentService.class);
        private final ApiInviteController controller = new ApiInviteController(inviteService, users, experiments);
        private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s").build();
        private final UserAccount me = member("me");

        Member() {
            when(users.viewer(jwt)).thenReturn(me);
        }

        @Test
        void page_withAnInvite_linksToTheWebJoinPage() {
            ReflectionTestUtils.setField(controller, "webUrl", "https://catapult.example");
            AlphaInvite invite = inviteOf(me);
            when(inviteService.getInvite(me)).thenReturn(Optional.of(invite));
            when(inviteService.getRedemptions(invite)).thenReturn(List.of(redemption("friend")));

            var page = controller.page(jwt);

            assertThat(page.canInvite()).isTrue();
            assertThat(page.inviteUrl()).isEqualTo("https://catapult.example/join?invite=ABCDEFGHJK");
            assertThat(page.redemptions()).extracting("inviteeTwitchId").containsExactly("friend");
            verify(experiments).track(me, ApiInviteController.EXPERIMENT_KEY, ApiInviteController.EVENT_PAGE_VIEW);
        }

        @Test
        void page_withoutWebUrl_isRelative() {
            when(inviteService.getInvite(me)).thenReturn(Optional.of(inviteOf(me)));

            assertThat(controller.page(jwt).inviteUrl()).isEqualTo("/join?invite=ABCDEFGHJK");
        }

        @Test
        void page_withoutInvite() {
            when(inviteService.getInvite(me)).thenReturn(Optional.empty());

            var page = controller.page(jwt);

            assertThat(page.canInvite()).isFalse();
            assertThat(page.redemptions()).isEmpty();
        }

        @Test
        void regenerate_isTracked() {
            controller.regenerate(jwt);

            verify(inviteService).regenerateCode(me);
            verify(experiments).track(me, ApiInviteController.EXPERIMENT_KEY, ApiInviteController.EVENT_LINK_REGENERATED);
        }
    }

    @Nested
    class Admin {
        private final UserAccountRepository users = mock(UserAccountRepository.class);
        private final ApiAdminInviteController controller = new ApiAdminInviteController(inviteService, users);

        @Test
        void page_listsInvites_andActiveHumanMembersWithoutOne() {
            UserAccount inviter = member("inviter");
            UserAccount uninvited = member("uninvited");
            UserAccount bot = member("bot");
            bot.setSystemAccount(true);
            UserAccount gone = member("gone");
            gone.setStatus(UserAccount.Status.PENDING_DELETION);
            AlphaInvite invite = inviteOf(inviter);
            invite.setCreatedAt(Instant.EPOCH);
            when(inviteService.findAll()).thenReturn(List.of(invite));
            when(inviteService.getRedemptions(invite)).thenReturn(List.of(redemption("friend")));
            when(users.findAll()).thenReturn(List.of(inviter, uninvited, bot, gone));
            when(inviteService.getGlobalMaxMembers()).thenReturn(Optional.of(100));
            when(inviteService.getDefaultMaxUses()).thenReturn(Optional.empty());
            when(inviteService.isGlobalCapReached()).thenReturn(true);

            var page = controller.page();

            assertThat(page.invites()).singleElement().satisfies(row -> {
                assertThat(row.ownerUsername()).isEqualTo("inviter");
                assertThat(row.code()).isEqualTo("ABCDEFGHJK");
                assertThat(row.useCount()).isEqualTo(2);
                assertThat(row.redemptions()).extracting("inviteeTwitchId").containsExactly("friend");
            });
            assertThat(page.membersWithoutInvite()).containsExactly(new MemberDto(uninvited.getId(), "uninvited"));
            assertThat(page.globalMaxMembers()).isEqualTo(100);
            assertThat(page.defaultMaxUses()).isNull();
            assertThat(page.globalCapReached()).isTrue();
        }

        @Test
        void settingsAndQuota_areSaved() {
            UUID inviteId = UUID.randomUUID();

            controller.updateSettings(new GlobalSettingsRequest(50, null, true));
            controller.updateQuota(inviteId, new AdminInviteQuotaRequest(3, false));

            verify(inviteService).setGlobalMaxMembers(50);
            verify(inviteService).setDefaultMaxUses(null);
            verify(inviteService).setDefaultCanReinvite(true);
            verify(inviteService).updateInviteQuota(inviteId, 3, false);
        }

        @Test
        void grant_needsAKnownMember() {
            UserAccount user = member("someone");
            when(users.findById(user.getId())).thenReturn(Optional.of(user));

            controller.grantInvite(user.getId());
            verify(inviteService).grantInvite(user);

            assertThatThrownBy(() -> controller.grantInvite(UUID.randomUUID()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }
}
