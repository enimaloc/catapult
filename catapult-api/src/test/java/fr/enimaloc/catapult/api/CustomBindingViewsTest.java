package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingScheduleDto;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.binding.schedule.CustomBindingScheduleService;
import fr.enimaloc.catapult.service.premium.PremiumService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import fr.enimaloc.catapult.service.premium.PremiumUiGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomBindingViewsTest {

    private final CustomBindingService service = mock(CustomBindingService.class);
    private final PremiumUiGate gate = mock(PremiumUiGate.class);
    private final CustomBindingScheduleService scheduleService = mock(CustomBindingScheduleService.class);
    private final CustomBindingScheduleRepository scheduleRepository = mock(CustomBindingScheduleRepository.class);
    private final PremiumService premium = mock(PremiumService.class);
    private final CustomBindingViews views =
            new CustomBindingViews(service, gate, scheduleService, scheduleRepository, premium);
    private final UserAccount user = new UserAccount();

    @BeforeEach
    void noBindings() {
        when(service.list(user)).thenReturn(List.of());
        when(service.active(user)).thenReturn(Optional.empty());
        user.setId(UUID.randomUUID());
    }

    private CustomBinding binding() {
        CustomBinding binding = new CustomBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        binding.setName("Art");
        return binding;
    }

    private static CustomBindingSchedule slot(CustomBinding binding, CustomBindingSchedule.Repeat repeat,
                                              LocalDateTime start, Set<DayOfWeek> days) {
        CustomBindingSchedule slot = new CustomBindingSchedule();
        slot.setId(UUID.randomUUID());
        slot.setCustomBinding(binding);
        slot.setStartsAt(start);
        slot.setEndsAt(start.plusHours(2));
        slot.setRepeat(repeat);
        slot.setDays(days);
        slot.setRepeatUntil(LocalDate.of(2099, 1, 1));
        return slot;
    }

    @Test
    void state_carriesTheSchedulesFromOneQuery() {
        CustomBinding art = binding();
        CustomBindingSchedule weekly = slot(art, CustomBindingSchedule.Repeat.WEEKLY,
                LocalDateTime.of(2026, 1, 5, 20, 0), Set.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY));
        CustomBindingSchedule past = slot(art, CustomBindingSchedule.Repeat.NONE,
                LocalDateTime.of(2020, 1, 1, 20, 0), Set.of());
        when(service.list(user)).thenReturn(List.of(art));
        when(scheduleRepository.findByCustomBindingUserId(user.getId())).thenReturn(List.of(weekly, past));
        when(scheduleService.suspended(user, art, past)).thenReturn(true);
        when(premium.canRepeatSchedules(user)).thenReturn(true);

        var state = views.state(user);

        assertThat(state.canRepeat()).isTrue();
        // Premium hidden from the owner (the gate is closed by default): the dashboard doesn't name it.
        assertThat(state.premiumUi()).isFalse();
        List<CustomBindingScheduleDto> dtos = state.schedules().get(art.getId());
        assertThat(dtos).extracting(CustomBindingScheduleDto::id).containsExactly(past.getId(), weekly.getId());
        assertThat(dtos.get(0).suspended()).isTrue();
        assertThat(dtos.get(0).nextStart()).isNull();
        assertThat(dtos.get(1).days()).containsExactly("MONDAY", "SUNDAY");
        assertThat(dtos.get(1).nextStart()).isNotNull();
        org.mockito.Mockito.verify(scheduleRepository, org.mockito.Mockito.times(1))
                .findByCustomBindingUserId(user.getId());
    }

    @Test
    void publicState_leavesTheSchedulesOut() {
        CustomBinding art = binding();
        when(service.list(user)).thenReturn(List.of(art));

        var state = views.publicState(user);

        assertThat(state.schedules()).isEmpty();
        assertThat(state.canRepeat()).isNull();
        assertThat(state.premiumUi()).isNull();
        org.mockito.Mockito.verifyNoInteractions(scheduleRepository);
    }

    @Test
    void state_carriesLimit() {
        when(gate.isOpenFor(user)).thenReturn(true);

        when(service.limit(user)).thenReturn(OptionalInt.of(5));
        assertThat(views.state(user).limit()).isEqualTo(5);
        // The dashboard is told premium is shown, to name it.
        assertThat(views.state(user).premiumUi()).isTrue();

        when(service.limit(user)).thenReturn(OptionalInt.empty());
        assertThat(views.state(user).limit()).isNull();
    }

    @Test
    void state_leavesTheLimitOutWhilePremiumIsHidden() {
        when(service.limit(user)).thenReturn(OptionalInt.of(5));

        assertThat(views.state(user).limit()).isNull();
    }
}
