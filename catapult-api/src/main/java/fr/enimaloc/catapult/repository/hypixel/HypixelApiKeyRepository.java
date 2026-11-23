package fr.enimaloc.catapult.repository.hypixel;

import fr.enimaloc.catapult.domain.hypixel.HypixelApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** The shared Hypixel API keys. */
public interface HypixelApiKeyRepository extends JpaRepository<HypixelApiKeyEntry, String> {
    List<HypixelApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM HypixelApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<HypixelApiKeyEntry> findByExclusiveFalseWithOwner();
}
