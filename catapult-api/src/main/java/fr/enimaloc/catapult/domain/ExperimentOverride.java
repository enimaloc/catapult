package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "experiment_overrides")
@Getter
@Setter
public class ExperimentOverride {

    public enum OverrideType  { USER, ATTRIBUTE }
    public enum OverrideAction { FORCE_INCLUDE, FORCE_EXCLUDE, FORCE_VARIANT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    private Experiment experiment;

    @Enumerated(EnumType.STRING)
    @Column(name = "override_type", nullable = false)
    private OverrideType overrideType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OverrideAction action;

    @Column(nullable = false)
    private int priority = 0;

    // --- type USER ---
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private UserAccount targetUser;

    // --- type ATTRIBUTE ---
    @Column(name = "attribute_key")
    private String attributeKey;

    @Column(name = "attribute_op")
    private String attributeOp;

    @Column(name = "attribute_val")
    private String attributeVal;

    // --- action FORCE_VARIANT ---
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_variant_id")
    private ExperimentVariant targetVariant;
}
