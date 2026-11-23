package fr.enimaloc.catapult.repository.supercell;

import fr.enimaloc.catapult.domain.supercell.SupercellApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** The shared Supercell API keys, each of one game. */
public interface SupercellApiKeyRepository extends JpaRepository<SupercellApiKeyEntry, String> {
    List<SupercellApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM SupercellApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<SupercellApiKeyEntry> findByExclusiveFalseWithOwner();
}
