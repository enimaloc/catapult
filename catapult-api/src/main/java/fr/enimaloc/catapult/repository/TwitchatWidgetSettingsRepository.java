package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TwitchatWidgetSettingsRepository extends JpaRepository<TwitchatWidgetSettings, UUID> {

    void deleteByUser(UserAccount user);
}
