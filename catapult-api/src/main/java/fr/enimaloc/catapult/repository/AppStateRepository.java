package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.AppState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppStateRepository extends JpaRepository<AppState, String> {
}
