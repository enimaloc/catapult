package fr.enimaloc.catapult.service.premium;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private PremiumService service(String instant) {
        return new PremiumService(users, activityLog, 5, Clock.fixed(Instant.parse(instant), PremiumService.ZONE));
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
}
