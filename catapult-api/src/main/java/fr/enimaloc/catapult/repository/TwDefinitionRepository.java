package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.TwDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TwDefinitionRepository extends JpaRepository<TwDefinition, String> {
    List<TwDefinition> findAllByEnabledTrueOrderBySortOrderAscIdAsc();
    List<TwDefinition> findAllByOrderBySortOrderAscIdAsc();
}
