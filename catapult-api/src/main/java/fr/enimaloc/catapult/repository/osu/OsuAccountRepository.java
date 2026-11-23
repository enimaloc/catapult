package fr.enimaloc.catapult.repository.osu;

import fr.enimaloc.catapult.domain.osu.OsuAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** The members' osu! accounts. */
public interface OsuAccountRepository extends JpaRepository<OsuAccount, UUID> {

    /** Deletes the member's row whatever its version: an unlink must not lose to a concurrent token refresh. */
    @Modifying(flushAutomatically = true)
    @Query("delete from OsuAccount a where a.userId = :userId")
    int purgeByUserId(@Param("userId") UUID userId);

    /**
     * Marks every usable link invalid at {@code now} (the osu! application changed: its refresh tokens are dead),
     * bumping each version so that a renewal under way does not overwrite it. How many links.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OsuAccount a set a.invalidSince = :now, a.version = a.version + 1 where a.invalidSince is null")
    int invalidateAll(@Param("now") Instant now);
}
