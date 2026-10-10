package fr.enimaloc.catapult.repository.config;

import fr.enimaloc.catapult.domain.config.AppState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppStateRepository extends JpaRepository<AppState, String> {
}
