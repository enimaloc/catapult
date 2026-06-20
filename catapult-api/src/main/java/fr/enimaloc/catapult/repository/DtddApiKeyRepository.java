package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.DtddApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DtddApiKeyRepository extends JpaRepository<DtddApiKeyEntry, String> {
    List<DtddApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM DtddApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<DtddApiKeyEntry> findByExclusiveFalseWithOwner();
}
