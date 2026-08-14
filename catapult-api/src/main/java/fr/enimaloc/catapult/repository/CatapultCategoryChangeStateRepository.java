package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.CatapultCategoryChangeState;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CatapultCategoryChangeStateRepository extends JpaRepository<CatapultCategoryChangeState, UUID> {

    void deleteByUser(UserAccount user);
}
