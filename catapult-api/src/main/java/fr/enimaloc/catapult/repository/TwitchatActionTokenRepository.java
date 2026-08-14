package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchatActionToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

public interface TwitchatActionTokenRepository extends JpaRepository<TwitchatActionToken, UUID> {

    /**
     * {@code now} is passed in rather than using {@code CURRENT_TIMESTAMP}: the mapped columns are
     * {@link Instant}s, and Hibernate rejects comparing them to the SQL timestamp function.
     */
    @Modifying
    @Query("update TwitchatActionToken t set t.consumedAt = :now " +
           "where t.token = :token and t.consumedAt is null and t.expiresAt > :now")
    int consume(UUID token, Instant now);

    /** {@code userId} is a plain column here (no {@code @ManyToOne}), hence the by-id naming. */
    void deleteByUserId(UUID userId);
}
