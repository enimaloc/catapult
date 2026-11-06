package fr.enimaloc.catapult.service.premium;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.common.dto.premium.PremiumOfferDto;
import fr.enimaloc.catapult.common.dto.premium.PremiumStatusDto;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PremiumServiceTest {

    @Mock UserAccountRepository users;
    @Mock ActivityLogService activityLog;
    @Mock CustomBindingRepository bindings;

    private final MockEnvironment env = new MockEnvironment()
            .withProperty("app.polling.free-interval-seconds", "60")
            .withProperty("app.polling.premium-interval-seconds", "30");

    private PremiumService service(String instant) {
        return new PremiumService(users, activityLog, bindings, env, 5, Clock.fixed(Instant.parse(instant), PremiumService.ZONE));
    }

    private static UserAccount user(boolean premium, LocalDate until) {
        UserAccount user = new UserAccount();
        user.setPremium(premium);
        user.setPremiumUntil(until);
        return user;
    }

    @Test
    void premiumWithoutEnd_isPremium() {
        assertThat(service("2026-11-04T12:00:00Z").isPremium(user(true, null))).isTrue();
    }

    @Test
    void notPremium_isFree() {
        assertThat(service("2026-11-04T12:00:00Z").isPremium(user(false, null))).isFalse();
    }

    @Test
    void untilToday_isStillPremiumAt2359Paris() {
        assertThat(service("2026-11-04T22:59:00Z").isPremium(user(true, LocalDate.of(2026, 11, 4)))).isTrue();
    }

    @Test
    void untilYesterday_isFree() {
        assertThat(service("2026-11-04T23:00:00Z").isPremium(user(true, LocalDate.of(2026, 11, 4)))).isFalse();
    }

    @Test
    void customBindingLimit_freeIsCapped_premiumIsUnlimited() {
        PremiumService service = service("2026-11-04T12:00:00Z");
        assertThat(service.customBindingLimit(user(false, null))).isEqualTo(OptionalInt.of(5));
        assertThat(service.customBindingLimit(user(true, null))).isEqualTo(OptionalInt.empty());
    }

    @Test
    void grant_setsFlagAndDate_andLogsActivity() {
        UUID id = UUID.randomUUID();
        UserAccount account = user(false, null);
        when(users.findById(id)).thenReturn(Optional.of(account));

        service("2026-11-04T12:00:00Z").grant(id, LocalDate.of(2026, 12, 31), "admin-bob");

        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().isPremium()).isTrue();
        assertThat(saved.getValue().getPremiumUntil()).isEqualTo(LocalDate.of(2026, 12, 31));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(activityLog).addEntry(eq(id), eq("INFO"), message.capture());
        assertThat(message.getValue()).contains("admin-bob").contains("2026-12-31");
    }

    @Test
    void revoke_clearsFlagAndDate() {
        UUID id = UUID.randomUUID();
        UserAccount account = user(true, LocalDate.of(2026, 12, 31));
        when(users.findById(id)).thenReturn(Optional.of(account));

        service("2026-11-04T12:00:00Z").revoke(id, "admin-bob");

        assertThat(account.isPremium()).isFalse();
        assertThat(account.getPremiumUntil()).isNull();
        verify(users).save(account);
        verify(activityLog).addEntry(eq(id), eq("INFO"), any());
    }

    @Test
    void grant_unknownAccount_isNotFound() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service("2026-11-04T12:00:00Z").grant(id, null, "admin-bob"))
                .isInstanceOf(ApiProblem.class);
    }

    @Test
    void status_ofAFreeAccount_countsBindingsAndShowsTheLimit() {
        UserAccount account = user(false, null);
        when(bindings.countByUser(account)).thenReturn(3L);

        PremiumStatusDto status = service("2026-11-04T12:00:00Z").status(account);

        assertThat(status.premium()).isFalse();
        assertThat(status.until()).isNull();
        assertThat(status.customBindingCount()).isEqualTo(3);
        assertThat(status.customBindingLimit()).isEqualTo(5);
    }

    @Test
    void status_ofAPremiumAccount_isUnlimitedAndShowsTheEndDate() {
        UserAccount account = user(true, LocalDate.of(2026, 12, 31));
        when(bindings.countByUser(account)).thenReturn(12L);

        PremiumStatusDto status = service("2026-11-04T12:00:00Z").status(account);

        assertThat(status.premium()).isTrue();
        assertThat(status.until()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(status.customBindingCount()).isEqualTo(12);
        assertThat(status.customBindingLimit()).isNull();
    }

    @Test
    void status_ofAnExpiredAccount_isFree() {
        UserAccount account = user(true, LocalDate.of(2026, 11, 3));

        PremiumStatusDto status = service("2026-11-04T12:00:00Z").status(account);

        assertThat(status.premium()).isFalse();
        assertThat(status.until()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(status.customBindingLimit()).isEqualTo(5);
    }

    @Test
    void offer_inNormalMode_isTheEffectiveValuesAndTheDegradedFile() {
        assertThat(service("2026-11-04T12:00:00Z").offer()).isEqualTo(new PremiumOfferDto(60, 30, 90, 60, 5));
    }

    @Test
    void offer_followsAnAdminOverrideOfTheCurrentMode() {
        env.setProperty("app.polling.free-interval-seconds", "45");

        assertThat(service("2026-11-04T12:00:00Z").offer().freeIntervalSeconds()).isEqualTo(45);
    }

    @Test
    void offer_unparseableOverride_fallsBackWithoutThrowing() {
        env.setProperty("app.polling.free-interval-seconds", "30s");
        env.setProperty("app.polling.premium-interval-seconds", "");

        assertThat(service("2026-11-04T12:00:00Z").offer()).isEqualTo(new PremiumOfferDto(60, 30, 90, 60, 5));
    }

    @Test
    void offer_missingKeys_useTheSchedulerDefaults() {
        MockEnvironment bare = new MockEnvironment();
        PremiumService bareService = new PremiumService(users, activityLog, bindings, bare, 5,
                Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), PremiumService.ZONE));

        assertThat(bareService.offer()).isEqualTo(new PremiumOfferDto(60, 30, 90, 60, 5));
    }

    @Test
    void offer_inDegradedMode_takesTheDegradedColumnsFromTheEnvironmentAndTheNormalOnesFromTheFile() {
        MockEnvironment degraded = new MockEnvironment()
                .withProperty("app.polling.free-interval-seconds", "120")
                .withProperty("app.polling.premium-interval-seconds", "75");
        degraded.setActiveProfiles("degraded");
        PremiumService degradedService = new PremiumService(users, activityLog, bindings, degraded, 5,
                Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), PremiumService.ZONE));

        // The normal columns come from the test classpath's application.properties (60 / 60)
        assertThat(degradedService.offer()).isEqualTo(new PremiumOfferDto(60, 60, 120, 75, 5));
    }

    @Test
    void today_isTheDayInParis() {
        assertThat(service("2026-11-04T23:30:00Z").today()).isEqualTo(LocalDate.of(2026, 11, 5));
    }
}
