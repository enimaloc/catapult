package fr.enimaloc.catapult.repository.dtdd;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DtddMappingProposalRepository extends JpaRepository<DtddMappingProposal, UUID> {
    List<DtddMappingProposal> findByStatusOrderByCreatedAtAsc(Status status);
    Optional<DtddMappingProposal> findFirstByProposerAndIgdbIdAndStatus(UserAccount proposer, String igdbId, Status status);
    long countByIgdbIdAndStatus(String igdbId, Status status);
}
