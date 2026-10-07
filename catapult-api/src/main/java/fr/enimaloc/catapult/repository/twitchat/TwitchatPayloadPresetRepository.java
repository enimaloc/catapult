package fr.enimaloc.catapult.repository.twitchat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.twitchat.TwitchatPayloadPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TwitchatPayloadPresetRepository extends JpaRepository<TwitchatPayloadPreset, UUID> {
    List<TwitchatPayloadPreset> findByUser(UserAccount user);

    List<TwitchatPayloadPreset> findByUserAndEventType(UserAccount user, TwitchatNotificationEventType eventType);

    void deleteByUser(UserAccount user);
}
