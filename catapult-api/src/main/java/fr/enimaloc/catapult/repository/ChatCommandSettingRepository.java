package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatCommandSettingRepository extends JpaRepository<ChatCommandSetting, UUID> {
    List<ChatCommandSetting> findByUser(UserAccount user);
    Optional<ChatCommandSetting> findByUserAndKey(UserAccount user, String key);
    void deleteByUserAndKey(UserAccount user, String key);
}
