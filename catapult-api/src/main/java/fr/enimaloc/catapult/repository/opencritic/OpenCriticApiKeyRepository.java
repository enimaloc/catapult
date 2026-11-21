package fr.enimaloc.catapult.repository.opencritic;

import fr.enimaloc.catapult.domain.opencritic.OpenCriticApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OpenCriticApiKeyRepository extends JpaRepository<OpenCriticApiKeyEntry, String> {
    List<OpenCriticApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM OpenCriticApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<OpenCriticApiKeyEntry> findByExclusiveFalseWithOwner();
}
