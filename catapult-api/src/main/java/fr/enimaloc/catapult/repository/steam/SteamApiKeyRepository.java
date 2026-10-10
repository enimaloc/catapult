package fr.enimaloc.catapult.repository.steam;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SteamApiKeyRepository extends JpaRepository<SteamApiKeyEntry, String> {
    List<SteamApiKeyEntry> findByExclusiveFalse();

    @Query("SELECT e FROM SteamApiKeyEntry e LEFT JOIN FETCH e.owner WHERE e.exclusive = false")
    List<SteamApiKeyEntry> findByExclusiveFalseWithOwner();

    Optional<SteamApiKeyEntry> findByOwner(UserAccount owner);
    void deleteByOwner(UserAccount owner);
}
