package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, UUID> {

    Optional<OAuthToken> findByUserAndProvider(UserAccount user, OAuthToken.Provider provider);

    Optional<OAuthToken> findByProviderAndUserIsNull(OAuthToken.Provider provider);

    long countByExpiresAtAfter(Instant instant);

    long countByExpiresAtBefore(Instant instant);

    long countByRefreshTokenIsNull();
}
