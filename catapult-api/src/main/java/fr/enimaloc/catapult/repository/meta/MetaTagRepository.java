package fr.enimaloc.catapult.repository.meta;

import fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute;
import fr.enimaloc.catapult.domain.meta.MetaTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MetaTagRepository extends JpaRepository<MetaTag, Long> {
    Optional<MetaTag> findByAttributeAndKeyIgnoreCase(MetaTagAttribute attribute, String key);

    List<MetaTag> findAllByOrderByAttributeAscKeyAsc();
}
