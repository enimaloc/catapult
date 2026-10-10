package fr.enimaloc.catapult.repository.config;

import fr.enimaloc.catapult.domain.config.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
