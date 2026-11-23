package fr.enimaloc.catapult.repository.riot;

import fr.enimaloc.catapult.domain.riot.RiotAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** The members' declared Riot IDs. */
public interface RiotAccountRepository extends JpaRepository<RiotAccount, UUID> {
}
