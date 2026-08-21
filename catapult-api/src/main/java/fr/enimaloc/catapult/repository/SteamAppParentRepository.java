package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.SteamAppParentEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SteamAppParentRepository extends JpaRepository<SteamAppParentEntry, String> {
}
