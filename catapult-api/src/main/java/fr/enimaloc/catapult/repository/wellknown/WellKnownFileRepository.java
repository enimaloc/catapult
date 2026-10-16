package fr.enimaloc.catapult.repository.wellknown;

import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.domain.wellknown.WellKnownFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WellKnownFileRepository extends JpaRepository<WellKnownFile, Long> {
    Optional<WellKnownFile> findByLocationAndPath(WellKnownLocation location, String path);

    List<WellKnownFile> findAllByOrderByLocationAscPathAsc();
}
