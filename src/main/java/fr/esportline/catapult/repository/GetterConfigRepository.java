package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.GetterConfig;
import fr.esportline.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GetterConfigRepository extends JpaRepository<GetterConfig, UUID> {

    List<GetterConfig> findByUserOrderByPriorityAsc(UserAccount user);

    Optional<GetterConfig> findByUserAndProvider(UserAccount user, GetterConfig.Provider provider);
}
