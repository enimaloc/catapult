package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TwitchatWidgetSettingsRepository extends JpaRepository<TwitchatWidgetSettings, UUID> {
    Optional<TwitchatWidgetSettings> findByWidgetToken(UUID widgetToken);

    void deleteByUser(UserAccount user);
}
