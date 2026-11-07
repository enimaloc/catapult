package fr.enimaloc.catapult.repository.dtdd;

import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DtddGameMappingRepository extends JpaRepository<DtddGameMapping, String> {
}
