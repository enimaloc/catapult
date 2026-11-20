package fr.enimaloc.catapult.repository.emergency;

import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderEmergencyStopRepository extends JpaRepository<ProviderEmergencyStop, UUID> {

    @Query("select s from ProviderEmergencyStop s "
            + "where s.liftedAt is null and (s.expiresAt is null or s.expiresAt > :now)")
    List<ProviderEmergencyStop> findActive(@Param("now") Instant now);

    /** When a global stop of the provider (or of {@code ALL}) was last lifted. */
    @Query("select max(s.liftedAt) from ProviderEmergencyStop s "
            + "where s.scope = fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop.Scope.GLOBAL "
            + "and s.liftedAt is not null and (s.provider = :provider or s.provider = 'ALL')")
    Optional<Instant> findLastGlobalLiftedAt(@Param("provider") String provider);

    /** Active stops with their channel loaded, for views built outside a session. */
    @Query("select s from ProviderEmergencyStop s left join fetch s.channel "
            + "where s.liftedAt is null and (s.expiresAt is null or s.expiresAt > :now) order by s.createdAt desc")
    List<ProviderEmergencyStop> findActiveWithChannel(@Param("now") Instant now);

    /** Lifted or expired stops, newest first, with their channel loaded. */
    @Query("select s from ProviderEmergencyStop s left join fetch s.channel "
            + "where s.liftedAt is not null or (s.expiresAt is not null and s.expiresAt <= :now) "
            + "order by s.createdAt desc")
    List<ProviderEmergencyStop> findInactiveWithChannel(@Param("now") Instant now, Pageable pageable);
}
