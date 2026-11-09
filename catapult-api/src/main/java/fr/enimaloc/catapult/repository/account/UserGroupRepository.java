package fr.enimaloc.catapult.repository.account;

import fr.enimaloc.catapult.domain.account.UserGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {
    Optional<UserGroup> findByKey(String key);
    boolean existsByKey(String key);
}
