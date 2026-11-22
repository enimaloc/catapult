package fr.enimaloc.catapult.repository.bungie;

import fr.enimaloc.catapult.domain.bungie.BungieAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** The members' Bungie.net accounts. */
public interface BungieAccountRepository extends JpaRepository<BungieAccount, UUID> {

    /** Deletes the member's row whatever its version: an unlink must not lose to a concurrent token refresh. */
    @Modifying(flushAutomatically = true)
    @Query("delete from BungieAccount a where a.userId = :userId")
    int purgeByUserId(@Param("userId") UUID userId);
}
