package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;
import fr.enimaloc.catapult.common.dto.admin.RejectRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal.Status;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddMappingProposalRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddTopicsCacheRepository;
import fr.enimaloc.catapult.service.dtdd.DtddMappingEvents;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/dtdd-mapping")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminDtddMappingController {

    private final DtddMappingProposalRepository proposalRepo;
    private final DtddGameMappingRepository mappingRepo;
    private final DtddTopicsCacheRepository topicsRepo;
    private final DtddMappingEvents mappingEvents;

    @Autowired(required = false)
    private AdminEventPublisher events;

    /**
     * The proposals in {@code status}, oldest first, as catapult-web reads them (not the entities,
     * whose lazy proposer can't be serialized once the transaction is over).
     */
    @GetMapping("/proposals")
    @Transactional(readOnly = true)
    public List<DtddProposalDto> pending(@RequestParam(defaultValue = "PENDING") String status) {
        Status wanted;
        try {
            wanted = Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status " + status);
        }
        return proposalRepo.findByStatusOrderByCreatedAtAsc(wanted).stream()
                .map(ApiAdminDtddMappingController::toDto)
                .toList();
    }

    static DtddProposalDto toDto(DtddMappingProposal p) {
        DtddProposalDto.Proposer proposer = p.getProposer() == null
                ? null : new DtddProposalDto.Proposer(p.getProposer().getTwitchUsername());
        return new DtddProposalDto(p.getId(), p.getCreatedAt(), p.getIgdbId(), p.getProposedDtddId(), proposer,
                p.getReason(), p.getStatus().name());
    }

    @PostMapping("/proposals/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void approve(@PathVariable UUID id, @AuthenticationPrincipal UserAccount admin) {
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
        if (events != null) events.dtddProposalResolved(id.toString(), Status.APPROVED.name());
        mappingEvents.mappingChanged(p.getIgdbId());
    }

    @PostMapping("/proposals/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest body,
                       @AuthenticationPrincipal UserAccount admin) {
        DtddMappingProposal p = proposalRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (p.getStatus() != Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already resolved");
        }
        p.setStatus(Status.REJECTED);
        p.setResolver(admin);
        p.setResolvedAt(Instant.now());
        proposalRepo.save(p);
        if (events != null) events.dtddProposalResolved(id.toString(), Status.REJECTED.name());
        mappingEvents.mappingChanged(p.getIgdbId());
    }
}
