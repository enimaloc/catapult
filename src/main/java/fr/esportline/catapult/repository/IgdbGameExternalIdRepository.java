package fr.esportline.catapult.repository;

import fr.esportline.catapult.domain.IgdbGameExternalId;
import fr.esportline.catapult.domain.IgdbGameExternalIdPk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IgdbGameExternalIdRepository extends JpaRepository<IgdbGameExternalId, IgdbGameExternalIdPk> {

    /** Lookup inverse : trouve le jeu IGDB correspondant à un uid sur une plateforme. */
    Optional<IgdbGameExternalId> findBySourceIdAndUid(long sourceId, String uid);
}
