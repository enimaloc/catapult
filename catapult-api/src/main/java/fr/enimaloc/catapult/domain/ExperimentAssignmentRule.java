package fr.enimaloc.catapult.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "experiment_assignment_rules")
@Getter
@Setter
public class ExperimentAssignmentRule {

    public enum RuleType {
        RANDOM, ATTRIBUTE, MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    @JsonIgnore
    private Experiment experiment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleType ruleType;

    @Column(nullable = false)
    private int priority = 0;

    // RANDOM rule fields
    private Integer percentage;

    // ATTRIBUTE rule fields
    private String attributeKey;
    private String attributeOperator;
    private String attributeValue;
}
