package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.TwitchatPayloadPreset;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TwitchatPayloadPresetRepository extends JpaRepository<TwitchatPayloadPreset, UUID> {
    List<TwitchatPayloadPreset> findByUser(UserAccount user);

    List<TwitchatPayloadPreset> findByUserAndEventType(UserAccount user, TwitchatNotificationEventType eventType);

    void deleteByUser(UserAccount user);
}
