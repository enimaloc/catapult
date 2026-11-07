package fr.enimaloc.catapult.repository.steam;

import fr.enimaloc.catapult.domain.steam.SteamAppParentEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SteamAppParentRepository extends JpaRepository<SteamAppParentEntry, String> {
}
