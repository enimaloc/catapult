package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.AlphaInvite;
import fr.enimaloc.catapult.domain.AlphaInviteRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlphaInviteRedemptionRepository extends JpaRepository<AlphaInviteRedemption, UUID> {

    List<AlphaInviteRedemption> findByInvite(AlphaInvite invite);

    boolean existsByInviteeTwitchId(String inviteeTwitchId);

    List<AlphaInviteRedemption> findByInviteeTwitchId(String inviteeTwitchId);
}
