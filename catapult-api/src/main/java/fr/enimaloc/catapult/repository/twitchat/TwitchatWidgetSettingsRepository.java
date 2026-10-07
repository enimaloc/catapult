package fr.enimaloc.catapult.repository.twitchat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TwitchatWidgetSettingsRepository extends JpaRepository<TwitchatWidgetSettings, UUID> {

    void deleteByUser(UserAccount user);
}
