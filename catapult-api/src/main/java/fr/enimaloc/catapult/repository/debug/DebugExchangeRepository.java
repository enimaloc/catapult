package fr.enimaloc.catapult.repository.debug;

import fr.enimaloc.catapult.domain.debug.DebugExchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface DebugExchangeRepository extends JpaRepository<DebugExchange, UUID> {

    @Modifying
    @Query("delete from DebugExchange e where e.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from DebugExchange e where e.userId in :userIds")
    int deleteByUserIdIn(@Param("userIds") Collection<UUID> userIds);
}
