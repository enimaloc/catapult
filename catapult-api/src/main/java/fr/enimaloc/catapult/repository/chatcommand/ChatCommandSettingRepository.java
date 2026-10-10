package fr.enimaloc.catapult.repository.chatcommand;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatCommandSettingRepository extends JpaRepository<ChatCommandSetting, UUID> {
    List<ChatCommandSetting> findByUser(UserAccount user);
    Optional<ChatCommandSetting> findByUserAndKey(UserAccount user, String key);
    void deleteByUserAndKey(UserAccount user, String key);
}
