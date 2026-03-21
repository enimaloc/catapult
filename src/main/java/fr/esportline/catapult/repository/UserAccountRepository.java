package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTwitchId(String twitchId);

    List<UserAccount> findByBotEnabledTrueAndStatus(UserAccount.Status status);

    List<UserAccount> findByStatusAndDeletionRequestedAtBefore(UserAccount.Status status, Instant cutoff);
}
