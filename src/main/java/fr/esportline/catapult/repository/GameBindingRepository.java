package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameBindingRepository extends JpaRepository<GameBinding, UUID> {

    Optional<GameBinding> findByUserAndSourceIdAndSourceType(
        UserAccount user, String sourceId, GameBinding.SourceType sourceType
    );

    Page<GameBinding> findByUser(UserAccount user, Pageable pageable);

    Page<GameBinding> findByUserAndStatus(UserAccount user, GameBinding.Status status, Pageable pageable);

    Page<GameBinding> findByUserAndSourceType(UserAccount user, GameBinding.SourceType sourceType, Pageable pageable);

    List<GameBinding> findByUser(UserAccount user);
}
