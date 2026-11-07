package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule.Repeat;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface CustomBindingScheduleRepository extends JpaRepository<CustomBindingSchedule, UUID> {

    List<CustomBindingSchedule> findByCustomBindingOrderByStartsAtAsc(CustomBinding customBinding);

    List<CustomBindingSchedule> findByCustomBindingUserId(UUID userId);

    /**
     * {@link #findByCustomBindingUserId}, with their binding: the scheduler reads it with no session open. A load
     * graph, so the binding's eager collections (read by {@code toGameBinding}) are still fetched.
     */
    @EntityGraph(attributePaths = "customBinding", type = EntityGraph.EntityGraphType.LOAD)
    List<CustomBindingSchedule> findWithBindingByCustomBindingUserId(UUID userId);

    /**
     * The streamers the scheduler has something to look at for: an unpaused slot that can still fire, a one-off one
     * ending from {@code since} on, or a repeated one not ended before that day. {@code since}: a wall time,
     * early enough for every zone.
     */
    @Query("""
            select distinct s.customBinding.user.id from CustomBindingSchedule s where s.paused = false
            and ((s.repeat = :once and s.endsAt >= :since)
              or (s.repeat <> :once and (s.repeatUntil is null or s.repeatUntil >= :sinceDay)))""")
    List<UUID> findUserIdsWithSchedulesStillDue(LocalDateTime since, LocalDate sinceDay, Repeat once);

    default List<UUID> findUserIdsWithSchedulesStillDue(LocalDateTime since) {
        return findUserIdsWithSchedulesStillDue(since, since.toLocalDate(), Repeat.NONE);
    }
}
