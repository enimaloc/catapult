package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.CatapultCategoryChangeState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CatapultCategoryChangeStateRepository extends JpaRepository<CatapultCategoryChangeState, UUID> {
}
