package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.DtddGameMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DtddGameMappingRepository extends JpaRepository<DtddGameMapping, String> {
}
