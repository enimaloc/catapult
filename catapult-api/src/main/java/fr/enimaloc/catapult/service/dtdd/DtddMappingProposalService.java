package fr.enimaloc.catapult.service.dtdd;

import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingProposalDto;
import fr.enimaloc.catapult.common.dto.channel.DtddProposalRequest;
import fr.enimaloc.catapult.common.dto.channel.StatusResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal.Status;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddMappingProposalRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A game's DTDD mapping and the proposals members make to change it: reading, validating, proposing, resolving. */
@Service
@RequiredArgsConstructor
public class DtddMappingProposalService {

    private final DtddGameMappingRepository mappingRepo;
    private final DtddGameCacheRepository gameRepo;
    private final DtddMappingProposalRepository proposalRepo;
    private final DtddTopicsCacheRepository topicsRepo;
    private final DtddMappingEvents mappingEvents;
    private final AdminEventPublisher events;

    /** The game's mapping as {@code viewerOrNull} sees it; {@code admin} lets it validate an unchallenged one. */
    public StatusResponse status(String igdbId, UserAccount viewerOrNull, boolean admin) {
        Optional<DtddGameMapping> mappingOpt = mappingRepo.findById(igdbId);
        Optional<DtddMappingProposal> myProposal = viewerOrNull == null ? Optional.empty()
            : proposalRepo.findFirstByProposerAndIgdbIdAndStatus(viewerOrNull, igdbId, Status.PENDING);
        long otherPending = proposalRepo.countByIgdbIdAndStatus(igdbId, Status.PENDING);
        boolean canValidate = admin
            && mappingOpt.isPresent()
            && !mappingOpt.get().isVerified()
            && otherPending == 0;

        DtddMappingCurrentDto current = mappingOpt.map(m -> {
            String name = null;
            if (m.getDtddId() != null) {
                name = gameRepo.findById(m.getDtddId()).map(DtddGameCache::getName).orElse(null);
            }
            return new DtddMappingCurrentDto(m.getDtddId(), name, m.getConfidence(), m.isVerified());
        }).orElse(null);

        DtddMappingProposalDto pending = myProposal.map(p ->
            new DtddMappingProposalDto(p.getId(), p.getProposedDtddId(), p.getReason())).orElse(null);
        return new StatusResponse(current, pending, canValidate);
    }

    @Transactional
    public void validate(String igdbId) {
        DtddGameMapping mapping = mappingRepo.findById(igdbId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (mapping.isVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mapping already verified");
        }
        if (proposalRepo.countByIgdbIdAndStatus(igdbId, Status.PENDING) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pending proposals exist");
        }
        mapping.setVerified(true);
        mapping.setConfidence(1.0);
        mappingRepo.save(mapping);
        mappingEvents.mappingChanged(igdbId);
    }

    @Transactional
    public void propose(String igdbId, DtddProposalRequest body, UserAccount user) {
        DtddMappingProposal p = new DtddMappingProposal();
        p.setId(UUID.randomUUID());
        p.setIgdbId(igdbId);
        p.setProposedDtddId(body.dtddId());
        p.setProposer(user);
        p.setReason(body.reason());
        p.setStatus(Status.PENDING);
        p.setCreatedAt(Instant.now());
        proposalRepo.save(p);
        // A pending proposal is part of the mapping's state: nobody may validate it directly meanwhile
        mappingEvents.mappingChanged(igdbId);
    }

    /**
     * The proposals in {@code wanted}, oldest first, as catapult-web reads them (not the entities,
     * whose lazy proposer can't be serialized once the transaction is over).
     */
    @Transactional(readOnly = true)
    public List<DtddProposalDto> proposals(Status wanted) {
        return proposalRepo.findByStatusOrderByCreatedAtAsc(wanted).stream()
                .map(DtddMappingProposalService::toDto)
                .toList();
    }

    private static DtddProposalDto toDto(DtddMappingProposal p) {
        DtddProposalDto.Proposer proposer = p.getProposer() == null
                ? null : new DtddProposalDto.Proposer(p.getProposer().getTwitchUsername());
        return new DtddProposalDto(p.getId(), p.getCreatedAt(), p.getIgdbId(), p.getProposedDtddId(), proposer,
                p.getReason(), p.getStatus().name());
    }

    @Transactional
    public void approve(UUID id, UserAccount admin) {
        DtddMappingProposal p = proposalRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (p.getStatus() != Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already resolved");
        }
        DtddGameMapping mapping = mappingRepo.findById(p.getIgdbId())
            .orElseGet(() -> {
                DtddGameMapping m = new DtddGameMapping();
                m.setIgdbId(p.getIgdbId());
                m.setResolvedAt(Instant.now());
                return m;
            });
        Long oldId = mapping.getDtddId();
        mapping.setDtddId(p.getProposedDtddId());
        mapping.setConfidence(1.0);
        mapping.setVerified(true);
        mapping.setResolvedAt(Instant.now());
        mappingRepo.save(mapping);

        if (oldId != null && !Objects.equals(oldId, p.getProposedDtddId())) {
            topicsRepo.deleteById(oldId);
        }
        p.setStatus(Status.APPROVED);
        p.setResolver(admin);
        p.setResolvedAt(Instant.now());
        proposalRepo.save(p);
        events.dtddProposalResolved(id.toString(), Status.APPROVED.name());
        mappingEvents.mappingChanged(p.getIgdbId());
    }

    @Transactional
    public void reject(UUID id, UserAccount admin) {
        DtddMappingProposal p = proposalRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (p.getStatus() != Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already resolved");
        }
        p.setStatus(Status.REJECTED);
        p.setResolver(admin);
        p.setResolvedAt(Instant.now());
        proposalRepo.save(p);
        events.dtddProposalResolved(id.toString(), Status.REJECTED.name());
        mappingEvents.mappingChanged(p.getIgdbId());
    }
}
