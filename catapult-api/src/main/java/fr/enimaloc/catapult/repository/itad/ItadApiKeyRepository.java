package fr.enimaloc.catapult.repository.itad;

import fr.enimaloc.catapult.domain.itad.ItadApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ItadApiKeyRepository extends JpaRepository<ItadApiKeyEntry, String> {
    List<ItadApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM ItadApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<ItadApiKeyEntry> findByExclusiveFalseWithOwner();
}
