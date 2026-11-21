package fr.enimaloc.catapult.repository.speedrun;

import fr.enimaloc.catapult.domain.speedrun.SpeedrunAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The members' speedrun.com accounts. The key checks write through the conditional updates below, which only touch
 * the row while it still holds the key that was checked: a key removed or replaced meanwhile is never brought back.
 */
public interface SpeedrunAccountRepository extends JpaRepository<SpeedrunAccount, UUID> {

    /** speedrun.com refused the key {@code apiKeyEnc} of {@code userId}; 0 when the row no longer holds it. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SpeedrunAccount a set a.keyInvalid = true, a.updatedAt = :now"
            + " where a.userId = :userId and a.apiKeyEnc = :apiKeyEnc")
    int markKeyInvalid(@Param("userId") UUID userId, @Param("apiKeyEnc") String apiKeyEnc, @Param("now") Instant now);

    /** speedrun.com accepted the key {@code apiKeyEnc} again, as this account; 0 when the row no longer holds it. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SpeedrunAccount a set a.keyUserId = :keyUserId, a.keyUserName = :keyUserName,"
            + " a.keyCheckedAt = :now, a.updatedAt = :now where a.userId = :userId and a.apiKeyEnc = :apiKeyEnc")
    int keyRechecked(@Param("userId") UUID userId, @Param("apiKeyEnc") String apiKeyEnc,
                     @Param("keyUserId") String keyUserId, @Param("keyUserName") String keyUserName,
                     @Param("now") Instant now);
}
