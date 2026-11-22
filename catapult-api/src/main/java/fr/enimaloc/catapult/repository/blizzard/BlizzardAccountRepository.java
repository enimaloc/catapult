package fr.enimaloc.catapult.repository.blizzard;

import fr.enimaloc.catapult.domain.blizzard.BlizzardAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** The members' Blizzard accounts. */
public interface BlizzardAccountRepository extends JpaRepository<BlizzardAccount, UUID> {
}
