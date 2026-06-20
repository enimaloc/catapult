package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dtdd_mapping_proposal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DtddMappingProposal {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "igdb_id", nullable = false, length = 64)
    private String igdbId;

    @Column(name = "proposed_dtdd_id")
    private Long proposedDtddId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposer_user_id", nullable = false)
    private UserAccount proposer;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolver_id")
    private UserAccount resolver;
}
