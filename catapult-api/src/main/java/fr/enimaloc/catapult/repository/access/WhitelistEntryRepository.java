package fr.enimaloc.catapult.repository.access;

import fr.enimaloc.catapult.domain.access.WhitelistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhitelistEntryRepository extends JpaRepository<WhitelistEntry, String> {
}
