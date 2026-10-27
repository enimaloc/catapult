package fr.enimaloc.catapult.repository.chatcommand;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatCommandSettingRepository extends JpaRepository<ChatCommandSetting, UUID> {
    List<ChatCommandSetting> findByUser(UserAccount user);
    Optional<ChatCommandSetting> findByUserAndKey(UserAccount user, String key);

    /** A derived delete removes each row it finds, which needs a transaction: its own, or the caller's. */
    @Transactional
    void deleteByUserAndKey(UserAccount user, String key);
}
