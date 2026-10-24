package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Keyed by the member's account id. */
public interface UserApiTokenRepository extends JpaRepository<UserApiToken, UUID> {

    Optional<UserApiToken> findByTokenHash(String tokenHash);
}
