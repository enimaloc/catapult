package fr.enimaloc.catapult.repository.faceit;

import fr.enimaloc.catapult.domain.faceit.FaceitApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** The shared FACEIT Data API keys. */
public interface FaceitApiKeyRepository extends JpaRepository<FaceitApiKeyEntry, String> {
    List<FaceitApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM FaceitApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<FaceitApiKeyEntry> findByExclusiveFalseWithOwner();
}
