package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.binding.CustomBindingScheduleRefusal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface CustomBindingScheduleRefusalRepository
        extends JpaRepository<CustomBindingScheduleRefusal, CustomBindingScheduleRefusal.Key> {

    boolean existsByScheduleIdAndOccurrenceStart(UUID scheduleId, Instant occurrenceStart);

    @Transactional
    @Modifying
    @Query("delete from CustomBindingScheduleRefusal r where r.occurrenceEnd < :cutoff")
    void deleteByOccurrenceEndBefore(@Param("cutoff") Instant cutoff);
}
