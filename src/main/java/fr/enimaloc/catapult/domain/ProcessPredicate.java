package fr.enimaloc.catapult.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "process_predicate")
@Getter
@Setter
public class ProcessPredicate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "binding_id", nullable = false)
    private ProcessBinding binding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PredicateType type;

    /** How this predicate combines with the preceding one (ignored for the first predicate). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Connector connector = Connector.AND;

    /** Variable name — only used when type == ENV_VAR. */
    @Column(name = "env_key", length = 255)
    private String key;

    /** Expected value; may contain %VAR% (Windows) or $VAR (Unix) placeholders. */
    @Column(nullable = false, length = 500)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "os_target", nullable = false)
    private OsTarget osTarget = OsTarget.ALL;

    @Column(nullable = false)
    private int position = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum PredicateType {
        WORKING_DIRECTORY,
        ENV_VAR,
        CMDLINE_CONTAINS
    }

    public enum Connector {
        AND, OR
    }

    public enum OsTarget {
        ALL, WINDOWS, LINUX, MACOS;

        public boolean appliesTo(String clientOs) {
            return this == ALL || (clientOs != null && name().equalsIgnoreCase(clientOs));
        }
    }
}
