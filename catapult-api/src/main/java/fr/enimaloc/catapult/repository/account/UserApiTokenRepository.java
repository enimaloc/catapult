package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Keyed by the member's account id and the token's scope. */
public interface UserApiTokenRepository extends JpaRepository<UserApiToken, UserApiToken.Key> {

    Optional<UserApiToken> findByUserIdAndScope(UUID userId, ApiTokenScope scope);

    Optional<UserApiToken> findByTokenHash(String tokenHash);
}
