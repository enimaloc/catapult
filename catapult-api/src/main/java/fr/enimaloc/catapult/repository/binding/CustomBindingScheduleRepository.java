package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface CustomBindingScheduleRepository extends JpaRepository<CustomBindingSchedule, UUID> {

    List<CustomBindingSchedule> findByCustomBindingOrderByStartsAtAsc(CustomBinding customBinding);

    /**
     * With their binding: the scheduler reads it with no session open. A load graph, so the binding's eager
     * collections (read by {@code toGameBinding}) are still fetched.
     */
    @EntityGraph(attributePaths = "customBinding", type = EntityGraph.EntityGraphType.LOAD)
    List<CustomBindingSchedule> findByCustomBindingUserId(UUID userId);

    /** The streamers the scheduler has something to look at for. */
    @Query("select distinct s.customBinding.user.id from CustomBindingSchedule s where s.paused = false")
    List<UUID> findUserIdsWithUnpausedSchedules();
}
