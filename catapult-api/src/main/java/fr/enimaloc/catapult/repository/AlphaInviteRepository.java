package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.AlphaInvite;
import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AlphaInviteRepository extends JpaRepository<AlphaInvite, UUID> {

    Optional<AlphaInvite> findByOwner(UserAccount owner);

    Optional<AlphaInvite> findByCode(String code);
}
