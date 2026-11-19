package fr.enimaloc.catapult.repository.emergency;

import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProviderEmergencyStopRepository extends JpaRepository<ProviderEmergencyStop, UUID> {

    @Query("select s from ProviderEmergencyStop s "
            + "where s.liftedAt is null and (s.expiresAt is null or s.expiresAt > :now)")
    List<ProviderEmergencyStop> findActive(@Param("now") Instant now);
}
