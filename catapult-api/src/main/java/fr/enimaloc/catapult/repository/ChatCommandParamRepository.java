package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ChatCommandParam;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatCommandParamRepository extends JpaRepository<ChatCommandParam, UUID> {
    List<ChatCommandParam> findByUser(UserAccount user);
    Optional<ChatCommandParam> findByUserAndKey(UserAccount user, String key);
    void deleteByUserAndKey(UserAccount user, String key);
}
