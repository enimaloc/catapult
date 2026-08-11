package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchatActivePreset;
import fr.enimaloc.catapult.domain.TwitchatNotificationEventType;
import fr.enimaloc.catapult.domain.TwitchatPayloadPreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TwitchatActivePresetRepository
        extends JpaRepository<TwitchatActivePreset, TwitchatActivePreset.Key> {

    Optional<TwitchatActivePreset> findByUserIdAndEventType(UUID userId, TwitchatNotificationEventType eventType);

    List<TwitchatActivePreset> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);

    void deleteByPreset(TwitchatPayloadPreset preset);
}
