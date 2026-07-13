package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.MinecraftServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MinecraftServiceAccountRepository extends JpaRepository<MinecraftServiceAccount, UUID> {
    List<MinecraftServiceAccount> findByEnabledTrueOrderByFillOrderAsc();
}
