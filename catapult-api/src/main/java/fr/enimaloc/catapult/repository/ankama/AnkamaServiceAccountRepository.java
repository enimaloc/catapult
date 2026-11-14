package fr.enimaloc.catapult.repository.ankama;

import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnkamaServiceAccountRepository extends JpaRepository<AnkamaServiceAccount, UUID> {
    Optional<AnkamaServiceAccount> findByAnkamaAccountId(long ankamaAccountId);

    List<AnkamaServiceAccount> findByEnabledTrueAndAuthStateOrderByFillOrderAsc(
            AnkamaServiceAccount.AuthState authState);
}
