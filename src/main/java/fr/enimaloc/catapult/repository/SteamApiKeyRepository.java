package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SteamApiKeyRepository extends JpaRepository<SteamApiKeyEntry, String> {
    List<SteamApiKeyEntry> findByExclusiveFalse();
}
