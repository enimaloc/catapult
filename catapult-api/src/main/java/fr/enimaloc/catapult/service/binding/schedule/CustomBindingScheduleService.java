package fr.enimaloc.catapult.service.binding.schedule;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.binding.CustomBindingScheduleDto;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The time slots of a custom binding, edited as a whole list. Behind the same experiment as
 * {@link CustomBindingService}; what a free account may schedule is {@link PremiumService#canSchedule}'s call.
 *
 * <p>A slot a streamer is no longer entitled to (premium ended) stays stored but is {@linkplain #suspended
 * suspended}; it may be kept as it is or paused, never edited, so the others can still be changed.</p>
 */
@Service
@RequiredArgsConstructor
public class CustomBindingScheduleService {

    private static final Duration MAX_REPEATING_DURATION = Duration.ofHours(24);
    /** Long enough for a charity marathon (a ZEvent lasts three days). */
    private static final Duration MAX_ONE_OFF_DURATION = Duration.ofDays(7);

    private final CustomBindingScheduleRepository schedules;
    private final CustomBindingRepository bindings;
    private final CustomBindingService customBindingService;
    private final PremiumService premiumService;
    private final ApplicationEventPublisher eventPublisher;

    public List<CustomBindingSchedule> list(UserAccount user, UUID customBindingId) {
        CustomBinding binding = binding(user, customBindingId);
        return schedules.findByCustomBindingOrderByStartsAtAsc(binding);
    }

    /** Whether the owner no longer has the right to this slot: the scheduler leaves it alone. */
    public boolean suspended(UserAccount user, CustomBinding binding, CustomBindingSchedule schedule) {
        return !premiumService.canSchedule(user, binding.getTwitchGameId(), schedule.getRepeat());
    }

    /**
     * Makes {@code requests} the binding's slots: known ids are updated, absent ones deleted, null ids created.
     * Everything is validated before anything is written.
     */
    @Transactional
    public List<CustomBindingSchedule> replace(UserAccount user, UUID customBindingId,
                                               List<CustomBindingScheduleRequest> requests) {
        CustomBinding binding = binding(user, customBindingId);
        Map<UUID, CustomBindingSchedule> current = schedules.findByCustomBindingOrderByStartsAtAsc(binding).stream()
                .collect(Collectors.toMap(CustomBindingSchedule::getId, Function.identity()));

        Set<UUID> seen = new HashSet<>();
        List<Parsed> parsed = new ArrayList<>();
        for (CustomBindingScheduleRequest request : requests) {
            CustomBindingSchedule target = null;
            if (request.id() != null) {
                target = current.get(request.id());
                if (target == null || !seen.add(request.id())) {
                    throw invalid("Unknown schedule " + request.id());
                }
            }
            Parsed slot = parse(request);
            boolean unchanged = target != null && sameSlot(target, slot);
            if (!unchanged && !premiumService.canSchedule(user, binding.getTwitchGameId(), slot.repeat())) {
                throw new ApiProblem(ErrorCode.PREMIUM_REQUIRED, "This schedule needs Premium");
            }
            parsed.add(slot.withTarget(target));
        }

        List<CustomBindingSchedule> removed = current.values().stream().filter(s -> !seen.contains(s.getId())).toList();
        if (!removed.isEmpty()) {
            schedules.deleteAll(removed);
        }
        List<CustomBindingSchedule> result = new ArrayList<>();
        for (Parsed slot : parsed) {
            CustomBindingSchedule schedule = slot.target() != null ? slot.target() : new CustomBindingSchedule();
            schedule.setCustomBinding(binding);
            schedule.setStartsAt(slot.request().startsAt());
            schedule.setEndsAt(slot.request().endsAt());
            schedule.setRepeat(slot.repeat());
            schedule.setDays(new HashSet<>(slot.days()));
            schedule.setRepeatUntil(slot.request().repeatUntil());
            schedule.setTimeZone(slot.zone().getId());
            schedule.setPaused(slot.request().paused());
            result.add(schedules.save(schedule));
        }
        eventPublisher.publishEvent(new CustomBindingSchedulesChangedEvent(user.getId()));
        result.sort(Comparator.comparing(CustomBindingSchedule::getStartsAt));
        return result;
    }

    private record Parsed(CustomBindingScheduleRequest request, Repeat repeat, Set<DayOfWeek> days, ZoneId zone,
                          CustomBindingSchedule target) {
        Parsed withTarget(CustomBindingSchedule schedule) {
            return new Parsed(request, repeat, days, zone, schedule);
        }
    }

    private Parsed parse(CustomBindingScheduleRequest request) {
        if (request.startsAt() == null || request.endsAt() == null) {
            throw invalid("A schedule needs a start and an end");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw invalid("A schedule must end after it starts");
        }
        Repeat repeat;
        try {
            repeat = request.repeat() == null ? Repeat.NONE
                    : Repeat.valueOf(request.repeat().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("Unknown repeat " + request.repeat());
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (request.days() != null) {
            for (String day : request.days()) {
                try {
                    days.add(DayOfWeek.valueOf(day.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException | NullPointerException e) {
                    throw invalid("Unknown day " + day);
                }
            }
        }
        if (repeat != Repeat.NONE
                && Duration.between(request.startsAt(), request.endsAt()).compareTo(MAX_REPEATING_DURATION) > 0) {
            throw invalid("A repeating schedule lasts at most 24 hours");
        }
        if (repeat == Repeat.NONE
                && Duration.between(request.startsAt(), request.endsAt()).compareTo(MAX_ONE_OFF_DURATION) > 0) {
            throw invalid("A one-off schedule lasts at most 7 days");
        }
        if (repeat == Repeat.WEEKLY && days.isEmpty()) {
            throw invalid("A weekly schedule needs at least one day");
        }
        if (request.repeatUntil() != null && request.repeatUntil().isBefore(request.startsAt().toLocalDate())) {
            throw invalid("A schedule cannot stop repeating before it starts");
        }
        ZoneId zone;
        try {
            zone = request.timeZone() == null ? ZoneId.of(CustomBindingScheduleDto.ZONE)
                    : ZoneId.of(request.timeZone().trim());
        } catch (DateTimeException e) {
            throw invalid("Unknown time zone " + request.timeZone());
        }
        return new Parsed(request, repeat, days, zone, null);
    }

    /** Same slot but for {@code paused}: what an unchanged suspended slot looks like. */
    private static boolean sameSlot(CustomBindingSchedule existing, Parsed slot) {
        CustomBindingScheduleRequest r = slot.request();
        return existing.getStartsAt().equals(r.startsAt())
                && existing.getEndsAt().equals(r.endsAt())
                && existing.getRepeat() == slot.repeat()
                && existing.getDays().equals(slot.days())
                && Objects.equals(existing.getRepeatUntil(), r.repeatUntil())
                && existing.getTimeZone().equals(slot.zone().getId());
    }

    private CustomBinding binding(UserAccount user, UUID id) {
        if (!customBindingService.enabled(user)) {
            throw new ApiProblem(ErrorCode.FEATURE_DISABLED, "Feature not enabled");
        }
        return bindings.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown custom binding"));
    }

    private static ApiProblem invalid(String message) {
        return new ApiProblem(ErrorCode.INVALID, message);
    }
}
