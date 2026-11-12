package fr.enimaloc.catapult.domain.dtdd;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "dtdd_topics_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DtddTopicsCache {

    @Id
    @Column(name = "dtdd_id", nullable = false)
    private Long dtddId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "yes_topics", nullable = false, columnDefinition = "jsonb")
    private List<String> yesTopics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "no_topics", nullable = false, columnDefinition = "jsonb")
    private List<String> noTopics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mostly_topics", nullable = false, columnDefinition = "jsonb")
    private List<String> mostlyTopics;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** Each topic's severity by its name; null when fetched before they were kept, a topic without levels absent. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "severities", columnDefinition = "jsonb")
    private Map<String, DtddTopicSeverity> severities;

    /** Topics without their severities. */
    public DtddTopicsCache(Long dtddId, List<String> yesTopics, List<String> noTopics, List<String> mostlyTopics,
                           Instant fetchedAt) {
        this(dtddId, yesTopics, noTopics, mostlyTopics, fetchedAt, null);
    }
}
