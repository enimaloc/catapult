package fr.enimaloc.catapult.repository.supercell;

import fr.enimaloc.catapult.domain.supercell.SupercellAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** The members' declared Supercell player tags. */
public interface SupercellAccountRepository extends JpaRepository<SupercellAccount, UUID> {
}
