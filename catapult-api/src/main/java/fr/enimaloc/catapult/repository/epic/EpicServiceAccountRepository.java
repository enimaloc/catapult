package fr.enimaloc.catapult.repository.epic;

import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EpicServiceAccountRepository extends JpaRepository<EpicServiceAccount, UUID> {
    Optional<EpicServiceAccount> findByEpicAccountId(String epicAccountId);

    List<EpicServiceAccount> findByEnabledTrueAndAuthStateOrderByFillOrderAsc(EpicServiceAccount.AuthState authState);

    long countByEnabledTrueAndAuthState(EpicServiceAccount.AuthState authState);

    long countByEnabledTrueAndAuthStateAndFriendLimitReachedFalse(EpicServiceAccount.AuthState authState);
}
