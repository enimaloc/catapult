package fr.enimaloc.catapult.repository.binding;

import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GameMappingRepository extends JpaRepository<GameMapping, UUID> {

    Optional<GameMapping> findBySourceTypeAndSourceId(GameBinding.SourceType sourceType, String sourceId);
}
