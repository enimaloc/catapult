package fr.enimaloc.catapult.repository.riot;

import fr.enimaloc.catapult.domain.riot.RiotApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** The shared Riot API keys. */
public interface RiotApiKeyRepository extends JpaRepository<RiotApiKeyEntry, String> {
    List<RiotApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM RiotApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<RiotApiKeyEntry> findByExclusiveFalseWithOwner();
}
