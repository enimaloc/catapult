package fr.enimaloc.catapult.repository.twitch;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitch.CatapultCategoryChangeState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CatapultCategoryChangeStateRepository extends JpaRepository<CatapultCategoryChangeState, UUID> {

    void deleteByUser(UserAccount user);
}
