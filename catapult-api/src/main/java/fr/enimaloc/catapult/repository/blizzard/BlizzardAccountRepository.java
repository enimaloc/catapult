package fr.enimaloc.catapult.repository.blizzard;

import fr.enimaloc.catapult.domain.blizzard.BlizzardAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** The members' Blizzard accounts. */
public interface BlizzardAccountRepository extends JpaRepository<BlizzardAccount, UUID> {

    /** Deletes the member's row whatever its version: the purge must not lose to a concurrent settings write. */
    @Modifying(flushAutomatically = true)
    @Query("delete from BlizzardAccount a where a.userId = :userId")
    void purgeByUserId(@Param("userId") UUID userId);
}
