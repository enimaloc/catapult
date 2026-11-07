package fr.enimaloc.catapult.repository.tw;

import fr.enimaloc.catapult.domain.tw.TwDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TwDefinitionRepository extends JpaRepository<TwDefinition, String> {
    List<TwDefinition> findAllByEnabledTrueOrderBySortOrderAscIdAsc();
    List<TwDefinition> findAllByOrderBySortOrderAscIdAsc();
}
