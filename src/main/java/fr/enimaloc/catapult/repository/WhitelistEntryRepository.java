package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.WhitelistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhitelistEntryRepository extends JpaRepository<WhitelistEntry, String> {
}
