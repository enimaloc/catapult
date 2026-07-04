package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.*;
import fr.enimaloc.catapult.domain.DtddMappingProposal.Status;
import fr.enimaloc.catapult.repository.*;
import fr.enimaloc.catapult.service.notification.AdminEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
@RequiredArgsConstructor
public class ApiAdminDtddMappingController {

    private final DtddMappingProposalRepository proposalRepo;
    private final DtddGameMappingRepository mappingRepo;
    private final DtddTopicsCacheRepository topicsRepo;

    @Autowired(required = false)
    private AdminEventPublisher events;

    @GetMapping("/proposals")
    public List<DtddMappingProposal> pending(@RequestParam(defaultValue = "PENDING") String status) {
        return proposalRepo.findByStatusOrderByCreatedAtAsc(Status.valueOf(status));
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
    }

    public record RejectRequest(String reason) {}
}
