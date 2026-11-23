package fr.enimaloc.catapult.repository.faceit;

import fr.enimaloc.catapult.domain.faceit.FaceitAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** The members' declared FACEIT nicknames. */
public interface FaceitAccountRepository extends JpaRepository<FaceitAccount, UUID> {
}
