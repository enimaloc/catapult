package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GetterConfigRepository extends JpaRepository<GetterConfig, UUID> {

    List<GetterConfig> findByUserOrderByPriorityAsc(UserAccount user);
}
