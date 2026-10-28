package fr.enimaloc.catapult.repository.twitch;

import fr.enimaloc.catapult.domain.twitch.TwitchCclDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TwitchCclDefinitionRepository extends JpaRepository<TwitchCclDefinition, String> {
}
