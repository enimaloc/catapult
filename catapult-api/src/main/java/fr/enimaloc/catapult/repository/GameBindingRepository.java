package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
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

    Optional<GameBinding> findByIdAndUser(UUID id, UserAccount user);

    void deleteByUser(UserAccount user);

    List<GameBinding> findAllByStatusAndIgnoredFalse(GameBinding.Status status);

    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(b) > 0 FROM GameBinding b JOIN b.tws t WHERE t = :twId")
    boolean existsByTwsContaining(@org.springframework.data.repository.query.Param("twId") String twId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT b FROM GameBinding b WHERE b.ignored = false " +
        "AND b.twOverride = false AND b.status IN ('AUTO','MANUAL') " +
        "AND SIZE(b.tws) = 0")
    org.springframework.data.domain.Page<GameBinding> findCandidatesForTwBackfill(
        org.springframework.data.domain.Pageable pageable);
}
