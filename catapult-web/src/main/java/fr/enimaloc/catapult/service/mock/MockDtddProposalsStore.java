package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.admin.DtddProposalDto;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The mock's DTDD mapping proposals: one store for the whole application, so what a member proposes
 * reaches the admin review. Answers like catapult-api: 404 for an unknown proposal, 409 for one
 * already resolved. Lists oldest first.
 */
class MockDtddProposalsStore {
    private final Clock clock;
    private final List<DtddProposalDto> proposals = new ArrayList<>();

    MockDtddProposalsStore() {
        this(Clock.systemUTC());
    }

    MockDtddProposalsStore(Clock clock) {
        this.clock = clock;
        Instant now = clock.instant();
        add(now.minus(3, ChronoUnit.DAYS), "1942", 76882L, "alice", "Le jeu proposé n'est pas le bon", "APPROVED");
        add(now.minus(2, ChronoUnit.DAYS), "7346", 12045L, "bob", "Mauvaise édition", "REJECTED");
        add(now.minus(1, ChronoUnit.DAYS), "1020", 4521L, "alice", "Mapping erroné : c'est la suite", "PENDING");
        add(now.minus(2, ChronoUnit.HOURS), "11208", null, "carol", null, "PENDING");
    }

    synchronized List<DtddProposalDto> list(String status) {
        String wanted = "APPROVED".equals(status) || "REJECTED".equals(status) ? status : "PENDING";
        return proposals.stream().filter(p -> p.status().equals(wanted)).toList();
    }

    synchronized void propose(String igdbId, Long dtddId, String reason, String proposer) {
        add(clock.instant(), igdbId, dtddId, proposer, reason, "PENDING");
    }

    synchronized int approve(UUID id) {
        return resolve(id, "APPROVED");
    }

    /** The mock keeps no reason for a rejection: catapult-api does not either. */
    synchronized int reject(UUID id) {
        return resolve(id, "REJECTED");
    }

    private int resolve(UUID id, String status) {
        for (int i = 0; i < proposals.size(); i++) {
            DtddProposalDto p = proposals.get(i);
            if (!p.id().equals(id)) continue;
            if (!p.status().equals("PENDING")) return 409;
            proposals.set(i, new DtddProposalDto(p.id(), p.createdAt(), p.igdbId(), p.proposedDtddId(), p.proposer(),
                    p.reason(), status));
            return 204;
        }
        return 404;
    }

    private void add(Instant createdAt, String igdbId, Long dtddId, String proposer, String reason, String status) {
        proposals.add(new DtddProposalDto(UUID.randomUUID(), createdAt, igdbId, dtddId,
                proposer == null ? null : new DtddProposalDto.Proposer(proposer), reason, status));
    }
}
