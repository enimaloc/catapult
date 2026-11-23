package fr.enimaloc.catapult.repository.bungie;

import fr.enimaloc.catapult.domain.bungie.BungieAppCredentials;
import org.springframework.data.jpa.repository.JpaRepository;

/** The Bungie.net application credentials (one row at most). */
public interface BungieAppCredentialsRepository extends JpaRepository<BungieAppCredentials, Short> {
}
