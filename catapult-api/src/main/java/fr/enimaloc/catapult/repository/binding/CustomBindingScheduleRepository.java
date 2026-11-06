package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.binding.CustomBinding;
import fr.enimaloc.catapult.domain.binding.CustomBindingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface CustomBindingScheduleRepository extends JpaRepository<CustomBindingSchedule, UUID> {

    List<CustomBindingSchedule> findByCustomBindingOrderByStartsAtAsc(CustomBinding customBinding);

    List<CustomBindingSchedule> findByCustomBindingUserId(UUID userId);

    /** The streamers the scheduler has something to look at for. */
    @Query("select distinct s.customBinding.user.id from CustomBindingSchedule s where s.paused = false")
    List<UUID> findUserIdsWithUnpausedSchedules();
}
