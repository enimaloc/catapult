package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.ConfigAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ConfigAuditRepository extends JpaRepository<ConfigAudit, UUID> {
    List<ConfigAudit> findByKeyOrderByChangedAtDesc(String key);

    List<ConfigAudit> findByModuleAndKeyOrderByChangedAtDesc(String module, String key);
}
