package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.IgdbGameExternalId;
import fr.enimaloc.catapult.domain.IgdbGameExternalIdPk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IgdbGameExternalIdRepository extends JpaRepository<IgdbGameExternalId, IgdbGameExternalIdPk> {

    /** Lookup inverse : trouve le jeu IGDB correspondant à un uid sur une plateforme. */
    Optional<IgdbGameExternalId> findBySourceIdAndUid(long sourceId, String uid);

    /** Lookup direct : trouve l'entrée externe d'une plateforme pour un jeu IGDB donné. */
    Optional<IgdbGameExternalId> findByIgdbIdAndSourceId(String igdbId, long sourceId);

    /** Bulk lookup : toutes les entrées d'une plateforme pour une liste d'IDs IGDB. */
    List<IgdbGameExternalId> findBySourceIdAndIgdbIdIn(long sourceId, Collection<String> igdbIds);

    /** Toutes les entrées externes pour une source donnée (ex : tous les Twitch IDs connus). */
    List<IgdbGameExternalId> findBySourceId(long sourceId);
}
