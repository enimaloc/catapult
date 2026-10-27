package fr.enimaloc.catapult.repository.twitchat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TwitchatWidgetSettingsRepository extends JpaRepository<TwitchatWidgetSettings, UUID> {

    void deleteByUser(UserAccount user);

    /**
     * Creates the user's row with the column defaults (relay off), unless it exists: two first
     * views racing each other then both succeed, where a second save() would hit the primary key.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO twitchat_widget_settings (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId);

    @Query("select s.user from TwitchatWidgetSettings s where s.enabled = true and s.eventActionsInitialized = false")
    List<UserAccount> findUsersAwaitingEventActions();
}
