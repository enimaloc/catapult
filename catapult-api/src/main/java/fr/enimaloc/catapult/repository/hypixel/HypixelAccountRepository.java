package fr.enimaloc.catapult.repository.hypixel;

import fr.enimaloc.catapult.domain.hypixel.HypixelAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** The members' declared Minecraft accounts for Hypixel. */
public interface HypixelAccountRepository extends JpaRepository<HypixelAccount, UUID> {
}
