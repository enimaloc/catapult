package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.AddToGroupRequest;
import fr.enimaloc.catapult.common.dto.FlagView;
import fr.enimaloc.catapult.common.dto.MemberSummary;
import fr.enimaloc.catapult.common.dto.MigrateRequest;
import fr.enimaloc.catapult.common.dto.SetFlagRequest;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserFlag;
import fr.enimaloc.catapult.domain.UserGroup;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserFlagRepository;
import fr.enimaloc.catapult.repository.UserGroupRepository;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.BotToggleService;
import fr.enimaloc.catapult.service.StreamStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ApiAdminMembersController called directly: the guards around each admin action. */
class ApiAdminMembersControllerTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final StreamStateService streamState = mock(StreamStateService.class);
    private final AccountService accountService = mock(AccountService.class);
    private final BotToggleService botToggle = mock(BotToggleService.class);
    private final AdminMigrationService migrations = mock(AdminMigrationService.class);
    private final Environment environment = mock(Environment.class);
    private final UserFlagRepository flags = mock(UserFlagRepository.class);
    private final UserGroupRepository groups = mock(UserGroupRepository.class);
    private final ApiAdminMembersController controller = new ApiAdminMembersController(
            accounts, streamState, accountService, botToggle, migrations, environment, flags, groups);

    private UserAccount member;

    @BeforeEach
    void setUp() {
        member = account("member", "tw-member");
        when(accounts.findById(any())).thenReturn(Optional.empty());
        when(accounts.findById(member.getId())).thenReturn(Optional.of(member));
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
    }

    private UserAccount account(String name, String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(name);
        account.setTwitchId(twitchId);
        account.setStatus(UserAccount.Status.ACTIVE);
        return account;
    }

    private UserAccount known(String name, String twitchId) {
        UserAccount account = account(name, twitchId);
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    private static Jwt jwt(String twitchId) {
        return Jwt.withTokenValue("t").header("alg", "none").claim("twitchId", twitchId).build();
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void unknownMembers_are404() {
        UUID unknown = UUID.randomUUID();

        assertStatus(() -> controller.toggleBot(unknown), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.getMember(unknown), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.targeting(unknown), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.deleteFlag(unknown, "k"), HttpStatus.NOT_FOUND);
    }

    @Test
    void page_listsMembersWithLiveStatus_andTheMockProfile() {
        when(accounts.findAll()).thenReturn(List.of(member));
        when(streamState.isLive(member)).thenReturn(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev", "mock"});

        ApiAdminMembersController.MembersPageData page = controller.page();

        assertThat(page.members()).containsExactly(member);
        assertThat(page.liveStatus()).containsEntry(member.getId(), true);
        assertThat(page.isMockProfile()).isTrue();
    }

    @Test
    void toggleBot_flipsTheCurrentState() {
        member.setBotEnabled(true);

        controller.toggleBot(member.getId());

        verify(botToggle).setBotEnabled(member, false);
    }

    @Test
    void getMember() {
        assertThat(controller.getMember(member.getId())).isEqualTo(new MemberSummary(member.getId(), "member"));
    }

    @Nested
    class Deletion {
        @Test
        void deletesOtherRegularAccounts() {
            controller.deleteAccount(member.getId(), jwt("tw-admin"));

            verify(accountService).deleteAccountImmediately(member);
        }

        @Test
        void refusesTheSystemAccountAndTheCallersOwn() {
            assertStatus(() -> controller.deleteAccount(member.getId(), jwt("tw-member")), HttpStatus.FORBIDDEN);
            member.setSystemAccount(true);
            assertStatus(() -> controller.deleteAccount(member.getId(), jwt("tw-admin")), HttpStatus.FORBIDDEN);
            verify(accountService, never()).deleteAccountImmediately(any());
        }
    }

    @Nested
    class Unlinking {
        @Test
        void steam_needsALinkedSteamAccount() {
            assertStatus(() -> controller.unlinkSteam(member.getId()), HttpStatus.BAD_REQUEST);

            member.setSteamId("7656");
            controller.unlinkSteam(member.getId());
            verify(accountService).disconnectProvider(member, OAuthToken.Provider.STEAM);
        }

        @Test
        void twitch_needsAnActiveAccount() {
            controller.unlinkTwitch(member.getId());
            verify(accountService).unlinkTwitch(member);

            member.setStatus(UserAccount.Status.PENDING_DELETION);
            assertStatus(() -> controller.unlinkTwitch(member.getId()), HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class Migration {
        private UserAccount target;

        @BeforeEach
        void setUp() {
            target = known("target", "tw-target");
        }

        @Test
        void migratesTheChosenParts() {
            controller.migrateData(member.getId(), new MigrateRequest(target.getId(), true, false, true));

            ArgumentCaptor<AdminMigrationService.MigrateOptions> options =
                    ArgumentCaptor.forClass(AdminMigrationService.MigrateOptions.class);
            verify(migrations).migrate(org.mockito.ArgumentMatchers.eq(member), org.mockito.ArgumentMatchers.eq(target), options.capture());
            assertThat(options.getValue()).isEqualTo(new AdminMigrationService.MigrateOptions(true, false, true));
        }

        @Test
        void refusesInvalidRequests() {
            assertStatus(() -> controller.migrateData(member.getId(), new MigrateRequest(UUID.randomUUID(), true, true, true)),
                    HttpStatus.NOT_FOUND);
            assertStatus(() -> controller.migrateData(member.getId(), new MigrateRequest(member.getId(), true, true, true)),
                    HttpStatus.BAD_REQUEST);
            assertStatus(() -> controller.migrateData(member.getId(), new MigrateRequest(target.getId(), false, false, false)),
                    HttpStatus.BAD_REQUEST);
            target.setSystemAccount(true);
            assertStatus(() -> controller.migrateData(member.getId(), new MigrateRequest(target.getId(), true, true, true)),
                    HttpStatus.BAD_REQUEST);
            member.setSystemAccount(true);
            assertStatus(() -> controller.migrateData(member.getId(), new MigrateRequest(target.getId(), true, true, true)),
                    HttpStatus.BAD_REQUEST);
            verify(migrations, never()).migrate(any(), any(), any());
        }
    }

    @Nested
    class SystemAccount {
        @Test
        void promotion_demotesThePreviousSystemAccount() {
            UserAccount previous = known("bot", "tw-bot");
            previous.setSystemAccount(true);
            when(accounts.findBySystemAccountTrue()).thenReturn(Optional.of(previous));

            controller.promoteToSystem(member.getId());

            assertThat(previous.isSystemAccount()).isFalse();
            assertThat(member.isSystemAccount()).isTrue();
            verify(accounts).save(previous);
            verify(accounts).save(member);
        }

        @Test
        void promotion_deletesAnIdentitylessPlaceholder() {
            UserAccount placeholder = known("placeholder", null);
            placeholder.setSystemAccount(true);
            when(accounts.findBySystemAccountTrue()).thenReturn(Optional.of(placeholder));

            controller.promoteToSystem(member.getId());

            verify(accounts).delete(placeholder);
        }

        @Test
        void promotion_withoutAPreviousSystemAccount() {
            when(accounts.findBySystemAccountTrue()).thenReturn(Optional.empty());

            controller.promoteToSystem(member.getId());

            assertThat(member.isSystemAccount()).isTrue();
        }

        @Test
        void promotion_refusesSystemOrIdentitylessTargets() {
            UserAccount identityless = known("nobody", null);
            assertStatus(() -> controller.promoteToSystem(identityless.getId()), HttpStatus.BAD_REQUEST);

            member.setSystemAccount(true);
            assertStatus(() -> controller.promoteToSystem(member.getId()), HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class Targeting {
        @Test
        void flags_areCreatedOrUpdated_withTrimmedKeys() {
            UserFlag existing = new UserFlag();
            existing.setFlagKey("beta");
            when(flags.findByUserAndFlagKey(member, "beta")).thenReturn(Optional.of(existing));
            when(flags.findByUserAndFlagKey(member, "new")).thenReturn(Optional.empty());

            controller.setFlag(member.getId(), new SetFlagRequest(" beta ", "on"));
            controller.setFlag(member.getId(), new SetFlagRequest("new", "1"));

            assertThat(existing.getFlagValue()).isEqualTo("on");
            ArgumentCaptor<UserFlag> saved = ArgumentCaptor.forClass(UserFlag.class);
            verify(flags, org.mockito.Mockito.times(2)).save(saved.capture());
            assertThat(saved.getAllValues().get(1).getUser()).isSameAs(member);
            assertThat(saved.getAllValues().get(1).getFlagKey()).isEqualTo("new");
        }

        @Test
        void flags_needAKey() {
            assertStatus(() -> controller.setFlag(member.getId(), new SetFlagRequest(null, "v")), HttpStatus.BAD_REQUEST);
            assertStatus(() -> controller.setFlag(member.getId(), new SetFlagRequest(" ", "v")), HttpStatus.BAD_REQUEST);
            verify(flags, never()).findByUserAndFlagKey(any(), anyString());
        }

        @Test
        void flags_areDeletedWhenPresent() {
            UserFlag flag = new UserFlag();
            when(flags.findByUserAndFlagKey(member, "beta")).thenReturn(Optional.of(flag));
            when(flags.findByUserAndFlagKey(member, "none")).thenReturn(Optional.empty());

            controller.deleteFlag(member.getId(), "beta");
            controller.deleteFlag(member.getId(), "none");

            verify(flags).delete(flag);
        }

        @Test
        void groups_membershipIsAddedAndRemoved() {
            UserGroup group = new UserGroup();
            group.setId(UUID.randomUUID());
            group.setKey("testers");
            when(groups.findByKey("testers")).thenReturn(Optional.of(group));
            when(groups.findById(group.getId())).thenReturn(Optional.of(group));

            controller.addToGroup(member.getId(), new AddToGroupRequest("testers"));
            assertThat(group.getMembers()).containsExactly(member);

            controller.removeFromGroup(member.getId(), group.getId());
            assertThat(group.getMembers()).isEmpty();
        }

        @Test
        void groups_mustExist() {
            when(groups.findByKey("nope")).thenReturn(Optional.empty());

            assertStatus(() -> controller.addToGroup(member.getId(), new AddToGroupRequest("nope")), HttpStatus.NOT_FOUND);
            assertStatus(() -> controller.removeFromGroup(member.getId(), UUID.randomUUID()), HttpStatus.NOT_FOUND);
        }

        @Test
        void targeting_listsFlagsAndGroupMemberships() {
            UserFlag flag = new UserFlag();
            flag.setFlagKey("beta");
            flag.setFlagValue("on");
            when(flags.findByUser(member)).thenReturn(List.of(flag));
            UserGroup testers = new UserGroup();
            testers.setKey("testers");
            testers.getMembers().add(member);
            UserGroup others = new UserGroup();
            others.setKey("others");
            when(groups.findAll()).thenReturn(List.of(testers, others));

            var targeting = controller.targeting(member.getId());

            assertThat(targeting.flags()).containsExactly(new FlagView("beta", "on"));
            assertThat(targeting.groupKeys()).containsExactly("testers");
        }
    }
}
