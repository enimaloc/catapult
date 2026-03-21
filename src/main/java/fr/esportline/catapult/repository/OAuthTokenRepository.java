package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, UUID> {

    Optional<OAuthToken> findByUserAndProvider(UserAccount user, OAuthToken.Provider provider);

    void deleteByUserAndProvider(UserAccount user, OAuthToken.Provider provider);
}
