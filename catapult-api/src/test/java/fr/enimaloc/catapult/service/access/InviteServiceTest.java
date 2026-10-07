package fr.enimaloc.catapult.service.access;

import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.config.SystemSetting;
import fr.enimaloc.catapult.repository.access.AlphaInviteRedemptionRepository;
import fr.enimaloc.catapult.repository.access.AlphaInviteRepository;
import fr.enimaloc.catapult.repository.access.WhitelistEntryRepository;
import fr.enimaloc.catapult.repository.config.SystemSettingRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

class InviteServiceTest {

    private final AlphaInviteRepository invites = mock(AlphaInviteRepository.class);
    private final AlphaInviteRedemptionRepository redemptions = mock(AlphaInviteRedemptionRepository.class);
    private final SystemSettingRepository settings = mock(SystemSettingRepository.class);
    private final WhitelistEntryRepository whitelistEntries = mock(WhitelistEntryRepository.class);
    private final WhitelistService whitelist = mock(WhitelistService.class);
    private final ExperimentService experiments = mock(ExperimentService.class);
    private final InviteService service = new InviteService(invites, redemptions, settings, whitelistEntries, whitelist, experiments);

    /** In-memory system_setting table. */
    private final Map<String, SystemSetting> stored = new HashMap<>();
    private UserAccount owner;

    @BeforeEach
    void setUp() {
        owner = new UserAccount();
        owner.setId(UUID.randomUUID());
        when(settings.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        when(settings.save(any())).thenAnswer(call -> {
            SystemSetting setting = call.getArgument(0);
            stored.put(setting.getKey(), setting);
            return setting;
        });
        when(invites.save(any())).thenAnswer(call -> call.getArgument(0));
        when(invites.findByCode(anyString())).thenReturn(Optional.empty());
    }

    private void setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setKey(key);
        setting.setValue(value);
        stored.put(key, setting);
    }

    private AlphaInvite invite(String code) {
        AlphaInvite invite = new AlphaInvite();
        invite.setId(UUID.randomUUID());
        invite.setOwner(owner);
        invite.setCode(code);
        when(invites.findByCode(code)).thenReturn(Optional.of(invite));
        return invite;
    }

    @Nested
    class OwnInvite {
        @Test
        void existingInvite_isReturned() {
            AlphaInvite existing = new AlphaInvite();
            when(invites.findByOwner(owner)).thenReturn(Optional.of(existing));

            assertThat(service.getInvite(owner)).containsSame(existing);
        }

        @Test
        void isCreatedOnDemandWhileInvitesAndTheWhitelistAreOn() {
            service.setInviteEnabled(true);
            when(whitelist.isEnabled()).thenReturn(true);

            AlphaInvite created = service.getInvite(owner).orElseThrow();

            assertThat(created.getOwner()).isSameAs(owner);
            assertThat(created.getCode()).hasSize(10).matches("[A-HJ-NP-Z2-9]+");
        }

        @Test
        void noneWhileInvitesOrTheWhitelistAreOff() {
            when(whitelist.isEnabled()).thenReturn(true);
            assertThat(service.getInvite(owner)).isEmpty();

            service.setInviteEnabled(true);
            when(whitelist.isEnabled()).thenReturn(false);
            assertThat(service.getInvite(owner)).isEmpty();
            verify(invites, never()).save(any());
        }

        @Test
        void codesAreUnique() {
            when(invites.findByCode(anyString())).thenReturn(Optional.of(new AlphaInvite()), Optional.empty());

            AlphaInvite granted = service.grantInvite(owner);

            assertThat(granted.getCode()).isNotNull();
            verify(invites, org.mockito.Mockito.times(2)).findByCode(anyString());
        }

        @Test
        void givesUpAfterAHundredCollisions() {
            when(invites.findByCode(anyString())).thenReturn(Optional.of(new AlphaInvite()));

            assertThatThrownBy(() -> service.grantInvite(owner)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void grant_keepsAnExistingInvite() {
            AlphaInvite existing = new AlphaInvite();
            when(invites.findByOwner(owner)).thenReturn(Optional.of(existing));

            assertThat(service.grantInvite(owner)).isSameAs(existing);
        }

        @Test
        void regenerateCode() {
            AlphaInvite existing = invite("OLDCODE");
            when(invites.findByOwner(owner)).thenReturn(Optional.of(existing));

            service.regenerateCode(owner);

            assertThat(existing.getCode()).isNotEqualTo("OLDCODE");
            assertThat(existing.getRegeneratedAt()).isNotNull();
            verify(invites).save(existing);

            when(invites.findByOwner(owner)).thenReturn(Optional.empty());
            assertThat(service.regenerateCode(owner)).isEmpty();
        }
    }

    @Nested
    class Redemption {
        @Test
        void whitelistsTheInvitee_countsTheUse_andTracksTheInviter() {
            AlphaInvite invite = invite("ABCDEFGHJK");

            boolean canReinvite = service.redeem("abcdefghjk", "invitee-1");

            assertThat(canReinvite).isFalse();
            assertThat(invite.getUseCount()).isEqualTo(1);
            verify(whitelist).add("invitee-1");
            ArgumentCaptor<AlphaInviteRedemption> redemption = ArgumentCaptor.forClass(AlphaInviteRedemption.class);
            verify(redemptions).save(redemption.capture());
            assertThat(redemption.getValue().getInviteeTwitchId()).isEqualTo("invitee-1");
            verify(experiments).track(owner, "invite-button-placement", "invite_redeemed");
        }

        @Test
        void unknownCode_isRejected() {
            assertThatThrownBy(() -> service.redeem("NOPE", "x"))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                            e -> assertThat(e.getError().getErrorCode()).isEqualTo("invalid_invite"));
        }

        @Test
        void fullAlpha_isRejected() {
            setting(InviteService.KEY_GLOBAL_MAX_MEMBERS, "10");
            when(whitelistEntries.count()).thenReturn(10L);
            invite("CODE");

            assertThatThrownBy(() -> service.redeem("CODE", "x"))
                    .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                            e -> assertThat(e.getError().getErrorCode()).isEqualTo("alpha_full"));
            verify(whitelist, never()).add(any());
        }

        @Test
        void exhaustedInvite_isRejected_byItsOwnLimitOrTheDefault() {
            AlphaInvite invite = invite("CODE");
            invite.setMaxUses(1);
            invite.setUseCount(1);
            assertThatThrownBy(() -> service.redeem("CODE", "x")).isInstanceOf(OAuth2AuthenticationException.class);

            invite.setMaxUses(null);
            setting(InviteService.KEY_DEFAULT_MAX_USES, "1");
            assertThatThrownBy(() -> service.redeem("CODE", "x")).isInstanceOf(OAuth2AuthenticationException.class);
        }

        @Test
        void unlimitedWithoutAnyLimit() {
            AlphaInvite invite = invite("CODE");
            invite.setUseCount(1000);

            service.redeem("CODE", "x");

            assertThat(invite.getUseCount()).isEqualTo(1001);
        }

        @Test
        void canReinvite_comesFromTheInviteThenTheDefault() {
            AlphaInvite invite = invite("CODE");
            setting(InviteService.KEY_DEFAULT_CAN_REINVITE, "true");
            assertThat(service.redeem("CODE", "a")).isTrue();

            invite.setCanReinvite(false);
            assertThat(service.redeem("CODE", "b")).isFalse();
        }
    }

    @Nested
    class AdminSettings {
        @Test
        void unsetSettingsHaveDefaults() {
            assertThat(service.isInviteEnabled()).isFalse();
            assertThat(service.getGlobalMaxMembers()).isEmpty();
            assertThat(service.getDefaultMaxUses()).isEmpty();
            assertThat(service.getDefaultCanReinvite()).isFalse();
            assertThat(service.isGlobalCapReached()).isFalse();
        }

        @Test
        void settingsAreSavedAndReadBack() {
            service.setInviteEnabled(true);
            service.setGlobalMaxMembers(50);
            service.setDefaultMaxUses(3);
            service.setDefaultCanReinvite(true);

            assertThat(service.isInviteEnabled()).isTrue();
            assertThat(service.getGlobalMaxMembers()).contains(50);
            assertThat(service.getDefaultMaxUses()).contains(3);
            assertThat(service.getDefaultCanReinvite()).isTrue();
        }

        @Test
        void clearingANumericSettingDeletesIt() {
            service.setGlobalMaxMembers(null);
            service.setDefaultMaxUses(null);

            verify(settings).deleteById(InviteService.KEY_GLOBAL_MAX_MEMBERS);
            verify(settings).deleteById(InviteService.KEY_DEFAULT_MAX_USES);
        }

        @Test
        void malformedNumbersReadAsUnset() {
            setting(InviteService.KEY_GLOBAL_MAX_MEMBERS, "lots");
            setting(InviteService.KEY_DEFAULT_MAX_USES, " ");

            assertThat(service.getGlobalMaxMembers()).isEmpty();
            assertThat(service.getDefaultMaxUses()).isEmpty();
        }

        @Test
        void numbersAreTrimmed() {
            setting(InviteService.KEY_DEFAULT_MAX_USES, " 4 ");

            assertThat(service.getDefaultMaxUses()).contains(4);
        }

        @Test
        void updateInviteQuota() {
            AlphaInvite invite = new AlphaInvite();
            UUID id = UUID.randomUUID();
            when(invites.findById(id)).thenReturn(Optional.of(invite));

            service.updateInviteQuota(id, 5, true);

            assertThat(invite.getMaxUses()).isEqualTo(5);
            assertThat(invite.getCanReinvite()).isTrue();
            assertThatThrownBy(() -> service.updateInviteQuota(UUID.randomUUID(), 1, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void listings() {
            AlphaInvite invite = new AlphaInvite();
            when(invites.findAll()).thenReturn(List.of(invite));
            when(redemptions.findByInvite(invite)).thenReturn(List.of(new AlphaInviteRedemption()));

            assertThat(service.findAll()).containsExactly(invite);
            assertThat(service.getRedemptions(invite)).hasSize(1);
        }
    }
}
