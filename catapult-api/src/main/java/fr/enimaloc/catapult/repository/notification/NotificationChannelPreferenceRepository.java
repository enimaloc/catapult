package fr.enimaloc.catapult.repository.notification;

import fr.enimaloc.catapult.domain.notification.NotificationChannelPreference;
import fr.enimaloc.catapult.domain.notification.NotificationChannelPreferenceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationChannelPreferenceRepository
        extends JpaRepository<NotificationChannelPreference, NotificationChannelPreferenceId> {

    List<NotificationChannelPreference> findByIdUserId(UUID userId);
}
