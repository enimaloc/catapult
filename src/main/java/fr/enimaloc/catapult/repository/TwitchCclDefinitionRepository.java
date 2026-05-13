package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.TwitchCclDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TwitchCclDefinitionRepository extends JpaRepository<TwitchCclDefinition, String> {
}
