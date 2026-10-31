package fr.enimaloc.catapult.repository.igdb;

import fr.enimaloc.catapult.domain.igdb.IgdbGameExternalId;
import fr.enimaloc.catapult.domain.igdb.IgdbGameExternalIdPk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IgdbGameExternalIdRepository extends JpaRepository<IgdbGameExternalId, IgdbGameExternalIdPk> {

    /** Reverse lookup: finds the IGDB game matching a uid on a platform. */
    Optional<IgdbGameExternalId> findBySourceIdAndUid(long sourceId, String uid);

    /** Direct lookup: finds a platform's external entry for a given IGDB game. */
    Optional<IgdbGameExternalId> findByIgdbIdAndSourceId(String igdbId, long sourceId);

    /** Bulk lookup: every entry of a platform for a list of IGDB ids. */
    List<IgdbGameExternalId> findBySourceIdAndIgdbIdIn(long sourceId, Collection<String> igdbIds);

    /** Every external entry for a given source (e.g. all the known Twitch ids). */
    List<IgdbGameExternalId> findBySourceId(long sourceId);
}
