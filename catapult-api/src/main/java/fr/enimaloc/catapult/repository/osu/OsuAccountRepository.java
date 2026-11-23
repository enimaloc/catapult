package fr.enimaloc.catapult.repository.osu;

import fr.enimaloc.catapult.domain.osu.OsuAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** The members' osu! accounts. */
public interface OsuAccountRepository extends JpaRepository<OsuAccount, UUID> {

    /** Deletes the member's row whatever its version: an unlink must not lose to a concurrent token refresh. */
    @Modifying(flushAutomatically = true)
    @Query("delete from OsuAccount a where a.userId = :userId")
    int purgeByUserId(@Param("userId") UUID userId);
}
