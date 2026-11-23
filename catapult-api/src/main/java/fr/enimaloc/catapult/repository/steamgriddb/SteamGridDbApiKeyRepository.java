package fr.enimaloc.catapult.repository.steamgriddb;

import fr.enimaloc.catapult.domain.steamgriddb.SteamGridDbApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SteamGridDbApiKeyRepository extends JpaRepository<SteamGridDbApiKeyEntry, String> {
    List<SteamGridDbApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM SteamGridDbApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<SteamGridDbApiKeyEntry> findByExclusiveFalseWithOwner();
}
