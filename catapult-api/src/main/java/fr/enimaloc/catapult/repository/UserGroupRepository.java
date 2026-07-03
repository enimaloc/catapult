package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.UserGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {
    Optional<UserGroup> findByKey(String key);
    boolean existsByKey(String key);
}
