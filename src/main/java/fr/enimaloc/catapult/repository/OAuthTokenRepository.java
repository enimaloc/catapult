package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, UUID> {

    Optional<OAuthToken> findByUserAndProvider(UserAccount user, OAuthToken.Provider provider);

    void deleteByUserAndProvider(UserAccount user, OAuthToken.Provider provider);
}
