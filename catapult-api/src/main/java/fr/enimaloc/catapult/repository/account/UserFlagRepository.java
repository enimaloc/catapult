package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserFlagRepository extends JpaRepository<UserFlag, UUID> {
    List<UserFlag> findByUser(UserAccount user);
    Optional<UserFlag> findByUserAndFlagKey(UserAccount user, String flagKey);
}
