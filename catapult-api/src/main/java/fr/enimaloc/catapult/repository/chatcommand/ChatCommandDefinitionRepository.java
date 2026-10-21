package fr.enimaloc.catapult.repository.chatcommand;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatCommandDefinitionRepository extends JpaRepository<ChatCommandDefinition, UUID> {
    List<ChatCommandDefinition> findByUser(UserAccount user);
    Optional<ChatCommandDefinition> findByUserAndName(UserAccount user, String name);
    Optional<ChatCommandDefinition> findByUserAndPresetKey(UserAccount user, String presetKey);
    boolean existsByUserAndName(UserAccount user, String name);

    /** The active accounts with at least one enabled command: those to tell when the commands go. */
    @org.springframework.data.jpa.repository.Query("select distinct d.user from ChatCommandDefinition d "
        + "where d.enabled = true and d.user.status = fr.enimaloc.catapult.domain.account.UserAccount.Status.ACTIVE")
    List<UserAccount> findActiveUsersWithEnabledCommands();
}
