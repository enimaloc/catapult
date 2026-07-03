package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserFlagRepository extends JpaRepository<UserFlag, UUID> {
    List<UserFlag> findByUser(UserAccount user);
    Optional<UserFlag> findByUserAndFlagKey(UserAccount user, String flagKey);
}
