package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchatActionToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface TwitchatActionTokenRepository extends JpaRepository<TwitchatActionToken, UUID> {

    @Modifying
    @Query("update TwitchatActionToken t set t.consumedAt = CURRENT_TIMESTAMP " +
           "where t.token = :token and t.consumedAt is null and t.expiresAt > CURRENT_TIMESTAMP")
    int consume(UUID token);
}
