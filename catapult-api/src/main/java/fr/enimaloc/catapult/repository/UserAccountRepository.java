package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTwitchId(String twitchId);

    List<UserAccount> findByTwitchIdIn(Collection<String> twitchIds);

    Optional<UserAccount> findByTwitchUsername(String twitchUsername);

    List<UserAccount> findByBotEnabledTrueAndStatus(UserAccount.Status status);

    long countByBotEnabledTrueAndStatus(UserAccount.Status status);

    List<UserAccount> findBySteamIdNotNull();

    List<UserAccount> findByStatusAndDeletionRequestedAtBefore(UserAccount.Status status, Instant cutoff);

    List<UserAccount> findByStatusAndTwitchIdNotNull(UserAccount.Status status);

    List<UserAccount> findByStatus(UserAccount.Status status);

    Optional<UserAccount> findBySystemAccountTrue();

    Optional<UserAccount> findByWidgetToken(UUID widgetToken);
}
