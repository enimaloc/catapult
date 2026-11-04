package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.premium.PremiumService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PollCadenceTest {

    private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");

    private final MockEnvironment env = new MockEnvironment();
    private final PremiumService premiumService = mock(PremiumService.class);
    private final PollCadence cadence = new PollCadence(env, premiumService);

    private void intervals(String premium, String free) {
        env.setProperty(PollCadence.PREMIUM_KEY, premium);
        env.setProperty(PollCadence.FREE_KEY, free);
    }

    private UserAccount account(boolean premium) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        when(premiumService.isPremium(user)).thenReturn(premium);
        return user;
    }

    @Test
    void normalMode_tickIs30() {
        intervals("30", "60");

        assertThat(cadence.tickSeconds()).isEqualTo(30);
    }

    @Test
    void degradedMode_tickIs30() {
        intervals("60", "90");

        assertThat(cadence.tickSeconds()).isEqualTo(30);
    }

    @Test
    void neverPolled_isDue() {
        intervals("30", "60");

        assertThat(cadence.isDue(account(false), T0)).isTrue();
        assertThat(cadence.isDue(account(true), T0)).isTrue();
    }

    @Test
    void freeAccount_dueAfter60_notAfter30() {
        intervals("30", "60");
        UserAccount free = account(false);
        cadence.markPolled(free.getId(), T0);

        assertThat(cadence.isDue(free, T0.plusSeconds(30))).isFalse();
        assertThat(cadence.isDue(free, T0.plusSeconds(60))).isTrue();
    }

    @Test
    void freeAccount_tickSlightlyEarly_stillDue() {
        intervals("30", "60");
        UserAccount free = account(false);
        cadence.markPolled(free.getId(), T0);

        assertThat(cadence.isDue(free, T0.plusMillis(59_900))).isTrue();
    }

    @Test
    void premiumAccount_dueEvery30() {
        intervals("30", "60");
        UserAccount premium = account(true);
        cadence.markPolled(premium.getId(), T0);

        assertThat(cadence.isDue(premium, T0.plusSeconds(30))).isTrue();
        cadence.markPolled(premium.getId(), T0.plusSeconds(30));
        assertThat(cadence.isDue(premium, T0.plusSeconds(60))).isTrue();
    }

    @Test
    void degraded_freeAccountPolledEvery90() {
        intervals("60", "90");
        UserAccount free = account(false);
        long tick = cadence.tickSeconds();

        List<Long> polledAt = new ArrayList<>();
        for (long t = 0; t <= 90; t += tick) {
            Instant now = T0.plusSeconds(t);
            if (cadence.isDue(free, now)) {
                cadence.markPolled(free.getId(), now);
                polledAt.add(t);
            }
        }

        assertThat(polledAt).containsExactly(0L, 90L);
    }

    @Test
    void inconsistentOverride_premiumSlowerThanFree_eachFollowsItsOwn() {
        intervals("120", "40");
        UserAccount premium = account(true);
        UserAccount free = account(false);
        cadence.markPolled(premium.getId(), T0);
        cadence.markPolled(free.getId(), T0);

        assertThat(cadence.tickSeconds()).isEqualTo(40);
        assertThat(cadence.isDue(free, T0.plusSeconds(40))).isTrue();
        assertThat(cadence.isDue(premium, T0.plusSeconds(80))).isFalse();
        assertThat(cadence.isDue(premium, T0.plusSeconds(120))).isTrue();
    }

    @Test
    void zeroOrNegative_clampedTo1() {
        intervals("0", "-5");
        UserAccount free = account(false);
        cadence.markPolled(free.getId(), T0);

        assertThat(cadence.tickSeconds()).isEqualTo(1);
        assertThat(cadence.isDue(free, T0.plusSeconds(1))).isTrue();
    }

    @Test
    void unreadableOverride_fallsBackToDefault() {
        intervals("soon", "60");

        assertThat(cadence.tickSeconds()).isEqualTo(30);
    }

    @Test
    void seconds_areTheValuesTheLoopUses() {
        intervals("0", "30s");

        assertThat(cadence.premiumSeconds()).isEqualTo(1);
        assertThat(cadence.freeSeconds()).isEqualTo(60);
    }

    @Test
    void overrideChange_appliesOnNextCall() {
        intervals("30", "60");
        assertThat(cadence.tickSeconds()).isEqualTo(30);

        intervals("20", "60");

        assertThat(cadence.tickSeconds()).isEqualTo(20);
    }
}
