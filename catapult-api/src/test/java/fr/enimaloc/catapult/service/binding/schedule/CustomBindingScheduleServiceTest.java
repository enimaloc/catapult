package fr.enimaloc.catapult.service.binding.schedule;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingScheduleRequest;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule.Repeat;
import fr.enimaloc.catapult.event.CustomBindingSchedulesChangedEvent;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingScheduleRepository;
import fr.enimaloc.catapult.service.binding.CustomBindingService;
import fr.enimaloc.catapult.service.premium.PremiumService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomBindingScheduleServiceTest {

    private static final String SPECIAL_EVENTS = "509663";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 10, 20, 0);

    @Mock CustomBindingScheduleRepository schedules;
    @Mock CustomBindingRepository bindings;
    @Mock CustomBindingService customBindingService;
    @Mock PremiumService premiumService;
    @Mock ApplicationEventPublisher events;
    @InjectMocks CustomBindingScheduleService service;

    private UserAccount user;
    private CustomBinding binding;
    private List<CustomBindingSchedule> stored;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        binding = new CustomBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        binding.setTwitchGameId(SPECIAL_EVENTS);
        stored = new ArrayList<>();
        lenient().when(customBindingService.enabled(user)).thenReturn(true);
        lenient().when(bindings.findByIdAndUser(binding.getId(), user)).thenReturn(Optional.of(binding));
        lenient().when(schedules.findByCustomBindingOrderByStartsAtAsc(binding)).thenAnswer(i -> List.copyOf(stored));
        lenient().when(schedules.save(any(CustomBindingSchedule.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(premiumService.canSchedule(eq(user), any(), any())).thenAnswer(i ->
                SPECIAL_EVENTS.equals(i.getArgument(1)) && i.getArgument(2) == Repeat.NONE);
    }

    private static CustomBindingScheduleRequest request(UUID id, LocalDateTime start, LocalDateTime end, String repeat,
                                                        Set<String> days, boolean paused) {
        return new CustomBindingScheduleRequest(id, start, end, repeat, days, null, null, paused);
    }

    private static CustomBindingScheduleRequest oneOff() {
        return request(null, START, START.plusHours(2), "NONE", Set.of(), false);
    }

    private CustomBindingSchedule existing(Repeat repeat, Set<DayOfWeek> days) {
        CustomBindingSchedule s = new CustomBindingSchedule();
        s.setId(UUID.randomUUID());
        s.setCustomBinding(binding);
        s.setStartsAt(START);
        s.setEndsAt(START.plusHours(2));
        s.setRepeat(repeat);
        s.setDays(new java.util.HashSet<>(days));
        stored.add(s);
        return s;
    }

    private void assertProblem(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void freeStreamerSchedulesAOneOffSpecialEvent() {
        List<CustomBindingSchedule> result = service.replace(user, binding.getId(), List.of(oneOff()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRepeat()).isEqualTo(Repeat.NONE);
        verify(schedules).save(any(CustomBindingSchedule.class));
        verify(events).publishEvent(new CustomBindingSchedulesChangedEvent(user.getId()));
    }

    @Test
    void freeStreamerCannotRepeat() {
        var weekly = request(null, START, START.plusHours(2), "WEEKLY", Set.of("MONDAY"), false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(weekly)), ErrorCode.PREMIUM_REQUIRED);
        verifyNoInteractions(events);
    }

    @Test
    void freeStreamerCannotScheduleAnotherCategory() {
        binding.setTwitchGameId("509660");
        assertProblem(() -> service.replace(user, binding.getId(), List.of(oneOff())), ErrorCode.PREMIUM_REQUIRED);
    }

    @Test
    void suspendedScheduleCanStayUnchangedOrBePaused() {
        CustomBindingSchedule weekly = existing(Repeat.WEEKLY, Set.of(DayOfWeek.MONDAY));
        var paused = request(weekly.getId(), START, START.plusHours(2), "WEEKLY", Set.of("MONDAY"), true);

        List<CustomBindingSchedule> result = service.replace(user, binding.getId(), List.of(paused));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isPaused()).isTrue();
    }

    @Test
    void modifyingASuspendedScheduleNeedsPremium() {
        CustomBindingSchedule weekly = existing(Repeat.WEEKLY, Set.of(DayOfWeek.MONDAY));
        var moved = request(weekly.getId(), START.plusDays(1), START.plusDays(1).plusHours(2), "WEEKLY",
                Set.of("MONDAY"), false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(moved)), ErrorCode.PREMIUM_REQUIRED);
    }

    @Test
    void endMustFollowStart() {
        var bad = request(null, START, START, "NONE", Set.of(), false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(bad)), ErrorCode.INVALID);
    }

    @Test
    void repeatingSlotLastsAtMostADay() {
        var bad = request(null, START, START.plusHours(25), "DAILY", Set.of(), false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(bad)), ErrorCode.INVALID);
    }

    @Test
    void weeklyNeedsADay() {
        var bad = request(null, START, START.plusHours(2), "WEEKLY", Set.of(), false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(bad)), ErrorCode.INVALID);
    }

    @Test
    void unknownIdIsInvalid() {
        var bad = request(UUID.randomUUID(), START, START.plusHours(2), "NONE", Set.of(), false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(bad)), ErrorCode.INVALID);
    }

    @Test
    void missingIdsAreDeleted() {
        CustomBindingSchedule kept = existing(Repeat.NONE, Set.of());
        CustomBindingSchedule dropped = existing(Repeat.NONE, Set.of());
        var keep = request(kept.getId(), START, START.plusHours(2), "NONE", Set.of(), false);

        service.replace(user, binding.getId(), List.of(keep));

        verify(schedules).deleteAll(List.of(dropped));
    }

    @Test
    void theSlotKeepsTheBrowsersZone_orTheDefaultOne() {
        var tokyo = new CustomBindingScheduleRequest(null, START, START.plusHours(2), "NONE", Set.of(), null,
                "Asia/Tokyo", false);

        assertThat(service.replace(user, binding.getId(), List.of(tokyo)).get(0).getTimeZone())
                .isEqualTo("Asia/Tokyo");
        assertThat(service.replace(user, binding.getId(), List.of(oneOff())).get(0).getTimeZone())
                .isEqualTo("Europe/Paris");
    }

    @Test
    void aBindingHasAtMostTwentySlots() {
        List<CustomBindingScheduleRequest> slots = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> request(null, START.plusDays(i), START.plusDays(i).plusHours(1), "NONE", Set.of(), false))
                .toList();

        assertThat(service.replace(user, binding.getId(), slots.subList(0, 20))).hasSize(20);
        assertProblem(() -> service.replace(user, binding.getId(), slots), ErrorCode.INVALID);
    }

    @Test
    void aOneOffSlotLastsAtMostSevenDays() {
        var week = request(null, START, START.plusDays(7), "NONE", Set.of(), false);
        var longer = request(null, START, START.plusDays(7).plusMinutes(1), "NONE", Set.of(), false);

        assertThat(service.replace(user, binding.getId(), List.of(week))).hasSize(1);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(longer)), ErrorCode.INVALID);
    }

    @Test
    void unknownZoneIsInvalid() {
        var bad = new CustomBindingScheduleRequest(null, START, START.plusHours(2), "NONE", Set.of(), null,
                "Mars/Olympus", false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(bad)), ErrorCode.INVALID);
    }

    /** Moved to another zone is a change: a suspended slot cannot be. */
    @Test
    void movingASuspendedScheduleToAnotherZoneNeedsPremium() {
        CustomBindingSchedule weekly = existing(Repeat.WEEKLY, Set.of(DayOfWeek.MONDAY));
        var moved = new CustomBindingScheduleRequest(weekly.getId(), START, START.plusHours(2), "WEEKLY",
                Set.of("MONDAY"), null, "Asia/Tokyo", false);
        assertProblem(() -> service.replace(user, binding.getId(), List.of(moved)), ErrorCode.PREMIUM_REQUIRED);
    }

    /** Edited from a list that lacked a slot saved elsewhere since: refused, the slot kept. */
    @Test
    void aListEditedFromAnotherIsStale() {
        CustomBindingSchedule savedElsewhere = existing(Repeat.NONE, Set.of());
        CustomBindingSchedule shown = existing(Repeat.NONE, Set.of());
        var paused = request(shown.getId(), START, START.plusHours(2), "NONE", Set.of(), true);

        assertProblem(() -> service.replace(user, binding.getId(), List.of(shown.getId()), List.of(paused)),
                ErrorCode.STALE);
        verify(schedules, org.mockito.Mockito.never()).deleteAll(anyList());

        assertThat(service.replace(user, binding.getId(), List.of(shown.getId(), savedElsewhere.getId()),
                List.of(paused))).hasSize(1);
    }

    @Test
    void disabledExperimentIsRefused() {
        when(customBindingService.enabled(user)).thenReturn(false);
        assertProblem(() -> service.list(user, binding.getId()), ErrorCode.FEATURE_DISABLED);
    }

    @Test
    void anotherUsersBindingIsNotFound() {
        assertThatThrownBy(() -> service.list(user, UUID.randomUUID()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void suspendedFollowsCanSchedule() {
        CustomBindingSchedule weekly = existing(Repeat.WEEKLY, Set.of(DayOfWeek.MONDAY));
        assertThat(service.suspended(user, binding, weekly)).isTrue();
        assertThat(service.suspended(user, binding, existing(Repeat.NONE, Set.of()))).isFalse();
    }
}
