package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SteamApiKeyRepository extends JpaRepository<SteamApiKeyEntry, String> {
    List<SteamApiKeyEntry> findByExclusiveFalse();
    Optional<SteamApiKeyEntry> findByOwner(UserAccount owner);
    void deleteByOwner(UserAccount owner);
}
