package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwitchCclDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TwitchCclDefinitionRepository extends JpaRepository<TwitchCclDefinition, String> {
}
