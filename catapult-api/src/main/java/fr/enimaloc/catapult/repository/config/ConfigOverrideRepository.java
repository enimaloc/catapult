package fr.enimaloc.catapult.repository.config;

import fr.enimaloc.catapult.domain.config.ConfigOverride;
import fr.enimaloc.catapult.domain.config.ConfigOverrideId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigOverrideRepository extends JpaRepository<ConfigOverride, ConfigOverrideId> {

    List<ConfigOverride> findByIdModule(String module);

    Optional<ConfigOverride> findByIdModuleAndIdKey(String module, String key);

    default Optional<ConfigOverride> findApiByKey(String key) {
        return findByIdModuleAndIdKey(ConfigOverride.DEFAULT_MODULE, key);
    }
}
