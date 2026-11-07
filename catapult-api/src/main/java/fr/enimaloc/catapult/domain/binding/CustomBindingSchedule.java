package fr.enimaloc.catapult.domain.binding;

import fr.enimaloc.catapult.common.dto.binding.CustomBindingScheduleDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A time slot at which a custom binding switches itself on and off, once or repeated, in wall time of its zone. */
@Entity
@Table(name = "custom_binding_schedule")
@Getter
@Setter
public class CustomBindingSchedule {

    public enum Repeat { NONE, DAILY, WEEKLY }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "custom_binding_id", nullable = false)
    private CustomBinding customBinding;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "repeat", nullable = false)
    private Repeat repeat = Repeat.NONE;

    /** Weekdays of a {@link Repeat#WEEKLY} slot. */
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "custom_binding_schedule_day", joinColumns = @JoinColumn(name = "schedule_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week")
    private Set<DayOfWeek> days = new HashSet<>();

    /** Last day (inclusive) a repeating slot applies; null for no end. */
    @Column(name = "repeat_until")
    private LocalDate repeatUntil;

    /** IANA zone of {@code startsAt}, {@code endsAt} and {@code repeatUntil}: the browser's it was saved from. */
    @Column(name = "time_zone", nullable = false)
    private String timeZone = CustomBindingScheduleDto.ZONE;

    @Column(nullable = false)
    private boolean paused = false;

    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
